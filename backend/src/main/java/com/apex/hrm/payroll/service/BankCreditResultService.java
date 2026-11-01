package com.apex.hrm.payroll.service;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.payroll.domain.*;
import com.apex.hrm.payroll.repo.*;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import com.apex.hrm.platform.service.AuditService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Strict, whole-file import of a canonical bank result CSV:
 * EMPLOYEE_NO,AMOUNT,STATUS,REFERENCE. One outcome per exported employee.
 * File must come from an independently verified bank channel; upload is NOT proof of settlement.
 * No arbitrary bank layouts, newline-containing CSV fields, or partial imports are accepted.
 */
@Service
public class BankCreditResultService {
    public record ImportSummary(UUID importId, int accepted, int rejected,
                                BigDecimal acceptedAmount, BigDecimal rejectedAmount) {}
    private record ExportRow(UUID employeeId, String employeeNo, BigDecimal amount) {}
    private record ResponseRow(BigDecimal amount, String status, String bankReference) {}

    private final PayrollRunRepository runs;
    private final BankFileRepository files;
    private final BankTemplateRepository templates;
    private final PayrollEmployeeRepository payrollEmployees;
    private final EmployeeRepository employees;
    private final BankCreditImportRepository imports;
    private final BankCreditResultRepository results;
    private final PayrollBankSettlementRepository settlements;
    private final AuditService audit;

    public BankCreditResultService(PayrollRunRepository runs, BankFileRepository files,
            BankTemplateRepository templates, PayrollEmployeeRepository payrollEmployees,
            EmployeeRepository employees, BankCreditImportRepository imports,
            BankCreditResultRepository results, PayrollBankSettlementRepository settlements,
            AuditService audit) {
        this.runs = runs;
        this.files = files;
        this.templates = templates;
        this.payrollEmployees = payrollEmployees;
        this.employees = employees;
        this.imports = imports;
        this.results = results;
        this.settlements = settlements;
        this.audit = audit;
    }

    @Transactional
    public ImportSummary importResults(UUID bankFileId, String csv, String evidenceReference, UUID actor) {
        if (actor == null) throw new BusinessException("Authenticated bank result importer required");
        if (evidenceReference == null || evidenceReference.isBlank() || evidenceReference.length() > 255) {
            throw new BusinessException("Bank statement evidence reference is required (max 255 characters)");
        }
        if (csv == null || csv.isBlank() || csv.length() > 1_000_000) {
            throw new BusinessException("Bank result file must be non-empty and at most 1 MB");
        }
        BankFile bankFile = files.findById(bankFileId)
                .orElseThrow(() -> new NotFoundException("Bank file", bankFileId));
        PayrollRun run = runs.lockForSettlement(bankFile.getRunId())
                .orElseThrow(() -> new NotFoundException("Payroll run", bankFile.getRunId()));
        if (!"LOCKED".equals(run.getStatus())) throw new BusinessException("Bank results require locked, unpaid payroll");
        if (imports.existsByBankFileId(bankFileId) || settlements.existsByBankFileId(bankFileId)) {
            throw new BusinessException("Bank file already imported or acknowledged; corrections require separate exception workflow");
        }
        BankTemplate template = templates.findById(bankFile.getBankTemplateId())
                .orElseThrow(() -> new NotFoundException("Bank template", bankFile.getBankTemplateId()));
        Map<String, ExportRow> exported = parseExport(bankFile, template);
        Map<String, ResponseRow> response = parseResponse(csv);
        if (!exported.keySet().equals(response.keySet()))
            throw new BusinessException("Bank result employees must exactly match the exported file");

        // Prevent the same employee's credit being reported as accepted in multiple imported files.
        for (ExportRow row : exported.values()) {
            if ("ACCEPTED".equals(response.get(row.employeeNo()).status())
                    && !results.findByRunIdAndEmployeeIdAndCreditStatus(run.getId(), row.employeeId(), "ACCEPTED").isEmpty()) {
                throw new BusinessException("Employee already accepted in another bank result; resolve manually");
            }
        }

        // Validate the entire bank batch before making even one persistent write.
        for (ExportRow exp : exported.values()) {
            if (exp.amount().compareTo(response.get(exp.employeeNo()).amount()) != 0)
                throw new BusinessException("Bank result amount differs from exported employee amount");
        }
        BankCreditImport imported = new BankCreditImport();
        imported.setBankFileId(bankFileId);
        imported.setEvidenceReference(evidenceReference.strip());
        imported.setRecordedBy(actor);
        imported = imports.saveAndFlush(imported);
        int accepted = 0, rejected = 0;
        BigDecimal yes = BigDecimal.ZERO, no = BigDecimal.ZERO;
        for (ExportRow exp : exported.values()) {
            ResponseRow res = response.get(exp.employeeNo());
            BankCreditResult entry = new BankCreditResult();
            entry.setBankImportId(imported.getId());
            entry.setRunId(bankFile.getRunId());
            entry.setBankFileId(bankFileId);
            entry.setEmployeeId(exp.employeeId());
            entry.setEmployeeNo(exp.employeeNo());
            entry.setAmount(exp.amount());
            entry.setCreditStatus(res.status());
            entry.setBankReference(res.bankReference());
            results.save(entry);
            if ("ACCEPTED".equals(res.status())) { accepted++; yes = yes.add(exp.amount()); }
            else { rejected++; no = no.add(exp.amount()); }
        }
        audit.log("BankCreditImport", imported.getId(), "IMPORT",
                "Bank file " + bankFileId + " accepted " + accepted + ", rejected " + rejected
                        + "; evidence " + evidenceReference);
        return new ImportSummary(imported.getId(), accepted, rejected, yes, no);
    }

    /** Amount eligible for dual-control settlement confirmation, or null if not imported. */
    @Transactional(readOnly = true)
    public BigDecimal acceptedAmount(UUID bankFileId) {
        if (!imports.existsByBankFileId(bankFileId)) return null;
        return results.findByBankFileId(bankFileId).stream()
                .filter(r -> "ACCEPTED".equals(r.getCreditStatus()))
                .map(BankCreditResult::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional(readOnly = true)
    public List<BankCreditResult> results(UUID bankFileId) {
        BankFile bankFile = files.findById(bankFileId)
                .orElseThrow(() -> new NotFoundException("Bank file", bankFileId));
        runs.findById(bankFile.getRunId()).orElseThrow(() -> new NotFoundException("Payroll run", bankFile.getRunId()));
        return results.findByBankFileId(bankFileId);
    }

    private Map<String, ExportRow> parseExport(BankFile bankFile, BankTemplate template) {
        String delimiter = template.getDelimiter();
        if (delimiter == null || delimiter.length() != 1 || "\n\r\"".contains(delimiter)) {
            throw new BusinessException("Recovery import only supports one-character bank export delimiters");
        }
        List<String> cols = Arrays.stream(template.getColumnSpec().split(","))
                .map(String::strip).map(String::toUpperCase).toList();
        int numberIndex = cols.indexOf("EMPLOYEE_NO"), amountIndex = cols.indexOf("AMOUNT");
        if (numberIndex < 0 || amountIndex < 0)
            throw new BusinessException("Bank export must contain EMPLOYEE_NO and AMOUNT columns for results");
        Map<String, ExportRow> expected = new LinkedHashMap<>();
        for (PayrollEmployee pe : payrollEmployees.findByRunId(bankFile.getRunId())) {
            String number = employees.findById(pe.getEmployeeId()).orElseThrow().getEmployeeNo();
            if (expected.put(number, new ExportRow(pe.getEmployeeId(), number, pe.getNetPay())) != null)
                throw new BusinessException("Duplicate employee number in payroll run");
        }
        Map<String, ExportRow> exported = new LinkedHashMap<>();
        String[] lines = bankFile.getContent().split("\\r?\\n");
        int start = Boolean.TRUE.equals(template.getIncludeHeader()) ? 1 : 0;
        if (start == 1 && (lines.length == 0 || !splitCsvLine(lines[0], delimiter.charAt(0)).equals(cols)))
            throw new BusinessException("Bank export header differs from stored template");
        BigDecimal total = BigDecimal.ZERO;
        for (int i = start; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            List<String> fields = splitCsvLine(lines[i], delimiter.charAt(0));
            if (fields.size() != cols.size()) throw new BusinessException("Invalid stored bank export row");
            String number = fields.get(numberIndex);
            ExportRow employee = expected.get(number);
            if (employee == null) throw new BusinessException("Bank export references a non-payroll employee");
            BigDecimal amount = money(fields.get(amountIndex));
            if (employee.amount().compareTo(amount) != 0)
                throw new BusinessException("Bank export amount differs from frozen payroll");
            if (exported.put(number, employee) != null)
                throw new BusinessException("Duplicate employee in bank export");
            total = total.add(amount);
        }
        if (exported.size() != bankFile.getRecordCount() || total.compareTo(bankFile.getTotalAmount()) != 0)
            throw new BusinessException("Bank export totals or record count do not reconcile");
        return exported;
    }

    private static Map<String, ResponseRow> parseResponse(String csv) {
        String[] lines = csv.strip().split("\\r?\\n", -1);
        if (lines.length < 2 || lines.length > 10002
                || !lines[0].strip().equals("EMPLOYEE_NO,AMOUNT,STATUS,REFERENCE")) {
            throw new BusinessException("Expected EMPLOYEE_NO,AMOUNT,STATUS,REFERENCE header and 1-10000 rows");
        }
        Map<String, ResponseRow> rows = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            List<String> cells = splitCsvLine(lines[i], ',');
            if (cells.size() != 4) throw new BusinessException("Invalid bank response row " + (i + 1));
            String number = cells.get(0).strip(), status = cells.get(2).strip(), ref = cells.get(3).strip();
            if (number.isBlank() || ref.isBlank() || ref.length() > 120
                    || (!status.equals("ACCEPTED") && !status.equals("REJECTED"))) {
                throw new BusinessException("Invalid employee, result, or bank reference on row " + (i + 1));
            }
            if (rows.put(number, new ResponseRow(money(cells.get(1)), status, ref)) != null)
                throw new BusinessException("Duplicate employee in bank response");
        }
        return rows;
    }

    static BigDecimal money(String value) {
        try {
            BigDecimal d = new BigDecimal(value.strip());
            if (d.signum() <= 0 || d.stripTrailingZeros().scale() > 2)
                throw new NumberFormatException("Invalid cents");
            return d.setScale(2, RoundingMode.UNNECESSARY);
        } catch (RuntimeException ex) {
            throw new BusinessException("Bank amount must be positive and expressed in exact cents");
        }
    }

    static List<String> splitCsvLine(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false, closed = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { field.append('"'); i++; }
                else if (quoted) { quoted = false; closed = true; }
                else if (field.length() == 0 && !closed) quoted = true;
                else throw new BusinessException("Invalid bank CSV quote");
            } else if (c == delimiter && !quoted) {
                fields.add(field.toString()); field.setLength(0); closed = false;
            } else {
                if (closed || c == '\r' || c == '\n') throw new BusinessException("Invalid CSV field");
                field.append(c);
            }
        }
        if (quoted) throw new BusinessException("Unclosed bank CSV quote");
        fields.add(field.toString());
        return fields;
    }
}
