package com.apex.hrm.payroll.service;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.payroll.domain.BankFile;
import com.apex.hrm.payroll.domain.BankCreditResult;
import com.apex.hrm.payroll.domain.BankTemplate;
import com.apex.hrm.payroll.domain.PayPeriod;
import com.apex.hrm.payroll.domain.PayrollEmployee;
import com.apex.hrm.payroll.domain.PayrollRun;
import com.apex.hrm.payroll.repo.BankFileRepository;
import com.apex.hrm.payroll.repo.BankCreditImportRepository;
import com.apex.hrm.payroll.repo.BankCreditResultRepository;
import com.apex.hrm.payroll.repo.BankTemplateRepository;
import com.apex.hrm.payroll.repo.PayPeriodRepository;
import com.apex.hrm.payroll.repo.PayrollEmployeeRepository;
import com.apex.hrm.payroll.repo.PayrollRunRepository;
import com.apex.hrm.personnel.domain.Employee;
import com.apex.hrm.personnel.domain.EmployeeBankAccount;
import com.apex.hrm.personnel.repo.EmployeeBankAccountRepository;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import com.apex.hrm.platform.service.AuditService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generates a delimited bank crediting file from a per-tenant template (column order, delimiter, header).
 * Banks differ: add a dedicated formatter per bank when a fixed-width or proprietary layout is required.
 */
@Service
public class BankFileService {
    public record Result(BankFile file, List<String> skippedEmployees) {
    }

    private static final Set<String> COLUMNS = Set.of("ACCOUNT_NO", "ACCOUNT_NAME", "AMOUNT", "EMPLOYEE_NO", "EMPLOYEE_NAME", "BANK_CODE");

    private final PayrollRunRepository runs;
    private final PayPeriodRepository periods;
    private final PayrollEmployeeRepository payrollEmployees;
    private final BankTemplateRepository templates;
    private final BankFileRepository files;
    private final EmployeeRepository employees;
    private final EmployeeBankAccountRepository accounts;
    private final AuditService audit;
    private final BankCreditImportRepository resultImports;
    private final BankCreditResultRepository creditResults;

    public BankFileService(PayrollRunRepository runs, PayPeriodRepository periods, PayrollEmployeeRepository payrollEmployees,
                           BankTemplateRepository templates, BankFileRepository files, EmployeeRepository employees,
                           EmployeeBankAccountRepository accounts, AuditService audit,
                           BankCreditImportRepository resultImports, BankCreditResultRepository creditResults) {
        this.runs = runs;
        this.periods = periods;
        this.payrollEmployees = payrollEmployees;
        this.templates = templates;
        this.files = files;
        this.employees = employees;
        this.accounts = accounts;
        this.audit = audit;
        this.resultImports = resultImports;
        this.creditResults = creditResults;
    }

    @Transactional
    public Result generate(UUID runId, UUID bankTemplateId) {
        runs.lockForSettlement(runId).orElseThrow(() -> new NotFoundException("Payroll run", runId));
        if (!files.findByRunId(runId).isEmpty())
            throw new BusinessException("This payroll run already has a bank export; use rejected-credit recovery, not a duplicate full export");
        return build(runId, bankTemplateId, null, null);
    }

    /** Export only previously rejected employees; never replay accepted credits. */
    @Transactional
    public Result recoverRejected(UUID sourceFileId) {
        BankFile source = files.findById(sourceFileId)
                .orElseThrow(() -> new NotFoundException("Bank file", sourceFileId));
        PayrollRun run = runs.lockForSettlement(source.getRunId())
                .orElseThrow(() -> new NotFoundException("Payroll run", source.getRunId()));
        if (!"LOCKED".equals(run.getStatus()))
            throw new BusinessException("Recovery requires locked, unpaid payroll and finance review");
        if (!resultImports.existsByBankFileId(sourceFileId))
            throw new BusinessException("Import bank results before preparing recovery");
        if (files.existsByRecoverySourceFileId(sourceFileId))
            throw new BusinessException("A recovery export already exists for this bank file");
        List<BankCreditResult> bankResults = creditResults.findByBankFileId(sourceFileId);
        java.util.Set<UUID> rejected = new java.util.HashSet<>();
        for (BankCreditResult row : bankResults) {
            if (!"REJECTED".equals(row.getCreditStatus())) continue;
            if (!creditResults.findByRunIdAndEmployeeIdAndCreditStatus(source.getRunId(), row.getEmployeeId(), "ACCEPTED").isEmpty())
                throw new BusinessException("Employee has accepted bank credit; manual finance review required");
            rejected.add(row.getEmployeeId());
        }
        if (rejected.isEmpty()) throw new BusinessException("No rejected employee credits require recovery");
        return build(source.getRunId(), source.getBankTemplateId(), rejected, sourceFileId);
    }

    private Result build(UUID runId, UUID bankTemplateId, java.util.Set<UUID> selected, UUID sourceId) {
        PayrollRun run = runs.findById(runId).orElseThrow(() -> new NotFoundException("Payroll run", runId));
        if (!Set.of("APPROVED", "LOCKED", "PAID").contains(run.getStatus())) {
            throw new BusinessException("Bank files are generated for approved runs only (run is " + run.getStatus() + ")");
        }
        BankTemplate t = templates.findById(bankTemplateId).orElseThrow(() -> new NotFoundException("Bank template", bankTemplateId));
        PayPeriod period = periods.findById(run.getPayPeriodId()).orElseThrow();
        List<String> columns = Arrays.stream(t.getColumnSpec().split(",")).map(String::trim).map(String::toUpperCase).toList();
        for (String c : columns) {
            if (!COLUMNS.contains(c)) {
                throw new BusinessException("Unknown bank template column " + c + "; allowed: " + COLUMNS);
            }
        }

        StringBuilder sb = new StringBuilder();
        if (Boolean.TRUE.equals(t.getIncludeHeader())) {
            sb.append(String.join(t.getDelimiter(), columns)).append("\n");
        }
        List<String> skipped = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        for (PayrollEmployee pe : payrollEmployees.findByRunId(runId)) {
            if (selected != null && !selected.contains(pe.getEmployeeId())) continue;
            if (pe.getNetPay().signum() <= 0) {
                continue;
            }
            Employee emp = employees.findById(pe.getEmployeeId()).orElseThrow();
            EmployeeBankAccount acct = pickAccount(emp.getId(), t.getBankCode());
            if (acct == null) {
                skipped.add(emp.getEmployeeNo());
                continue;
            }
            List<String> row = new ArrayList<>();
            for (String c : columns) {
                String name = (emp.getLastName() + ", " + emp.getFirstName()).trim();
                row.add(csv(switch (c) {
                    case "ACCOUNT_NO" -> acct.getAccountNo();
                    case "ACCOUNT_NAME" -> acct.getAccountName() != null ? acct.getAccountName() : name;
                    case "AMOUNT" -> pe.getNetPay().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
                    case "EMPLOYEE_NO" -> emp.getEmployeeNo();
                    case "EMPLOYEE_NAME" -> name;
                    default -> acct.getBankCode();
                }, t.getDelimiter()));
            }
            sb.append(String.join(t.getDelimiter(), row)).append("\n");
            total = total.add(pe.getNetPay());
            count++;
        }

        if (selected != null && (count != selected.size() || !skipped.isEmpty()))
            throw new BusinessException("Recovery export missing one or more rejected employee bank accounts");
        BankFile f = new BankFile();
        f.setRunId(runId);
        f.setRecoverySourceFileId(sourceId);
        f.setBankTemplateId(bankTemplateId);
        f.setFileName(t.getBankCode() + "_payroll_" + period.getPayDate()
                + (sourceId == null ? "" : "_recovery_" + sourceId.toString().substring(0, 8)) + ".csv");
        f.setContent(sb.toString());
        f.setRecordCount(count);
        f.setTotalAmount(total);
        f = files.save(f);
        audit.log("BankFile", f.getId(), "GENERATE", count + " records, total " + total + ", skipped " + skipped.size());
        return new Result(f, skipped);
    }

    private EmployeeBankAccount pickAccount(UUID employeeId, String bankCode) {
        List<EmployeeBankAccount> list = accounts.findByEmployeeId(employeeId);
        return list.stream().filter(a -> a.getBankCode().equalsIgnoreCase(bankCode))
                .sorted((a, b) -> Boolean.compare(Boolean.TRUE.equals(b.getPrimaryAccount()), Boolean.TRUE.equals(a.getPrimaryAccount())))
                .findFirst().orElse(null);
    }

    private static String csv(String v, String delimiter) {
        if (v == null) {
            return "";
        }
        if (v.contains(delimiter) || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
