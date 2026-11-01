package com.apex.hrm.payroll.web;

import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.payroll.domain.Adjustment;
import com.apex.hrm.payroll.domain.BankFile;
import com.apex.hrm.payroll.domain.JournalEntry;
import com.apex.hrm.payroll.domain.PayrollEmployee;
import com.apex.hrm.payroll.domain.PayrollLine;
import com.apex.hrm.payroll.domain.PayrollRun;
import com.apex.hrm.payroll.repo.BankFileRepository;
import com.apex.hrm.payroll.repo.PayrollEmployeeRepository;
import com.apex.hrm.payroll.repo.PayrollLineRepository;
import com.apex.hrm.payroll.service.AdjustmentService;
import com.apex.hrm.payroll.service.BankFileService;
import com.apex.hrm.payroll.service.JournalService;
import com.apex.hrm.payroll.service.PayrollService;
import com.apex.hrm.personnel.domain.Employee;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import com.apex.hrm.org.domain.Company;
import com.apex.hrm.org.repo.CompanyRepository;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/** Payroll run lifecycle, adjustments, and accounting/bank outputs. */
@RestController
@RequestMapping("/api/payroll")
public class PayrollController {
    public record FinalPayRequest(@NotNull UUID payPeriodId, @NotNull UUID employeeId) {
    }

    public record CreateRunRequest(UUID payPeriodId, String runType) {
    }

    public record BankFileRequest(UUID bankTemplateId) {
    }

    public record AdjustmentRequest(UUID employeeId, String itemCode, String description, String category, BigDecimal amount,
                                    Boolean taxable, String reason, UUID sourcePeriodId, UUID targetPeriodId, UUID requestedBy) {
    }

    private final PayrollService payroll;
    private final JournalService journals;
    private final BankFileService bankFiles;
    private final AdjustmentService adjustments;
    private final PayrollEmployeeRepository payrollEmployees;
    private final PayrollLineRepository lines;
    private final BankFileRepository bankFileRepo;
    private final EmployeeRepository employees;
    private final CompanyRepository companies;

    public PayrollController(PayrollService payroll, JournalService journals, BankFileService bankFiles,
                             AdjustmentService adjustments, PayrollEmployeeRepository payrollEmployees,
                             PayrollLineRepository lines, BankFileRepository bankFileRepo,
                             EmployeeRepository employees, CompanyRepository companies) {
        this.payroll = payroll;
        this.journals = journals;
        this.bankFiles = bankFiles;
        this.adjustments = adjustments;
        this.payrollEmployees = payrollEmployees;
        this.lines = lines;
        this.bankFileRepo = bankFileRepo;
        this.employees = employees;
        this.companies = companies;
    }

    /** Minimal employee directory for payroll staff; avoids exposing private employee fields. */
    public record EmployeeSummary(UUID id, String employeeNo, String firstName, String lastName, String status,
                                  LocalDate separationDate) {
        static EmployeeSummary from(Employee employee) {
            return new EmployeeSummary(employee.getId(), employee.getEmployeeNo(), employee.getFirstName(),
                    employee.getLastName(), employee.getStatus(), employee.getSeparationDate());
        }
    }

    @GetMapping("/employees")
    public Page<EmployeeSummary> employeeDirectory(Pageable pageable) {
        return employees.findAll(pageable).map(EmployeeSummary::from);
    }

    public record CompanySummary(UUID id, String legalName, String tradeName) {
        static CompanySummary from(Company company) {
            return new CompanySummary(company.getId(), company.getLegalName(), company.getTradeName());
        }
    }

    @GetMapping("/companies")
    public List<CompanySummary> companyDirectory() {
        return companies.findAll(Sort.by(Sort.Direction.ASC, "legalName")).stream().map(CompanySummary::from).toList();
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public PayrollRun create(@RequestBody CreateRunRequest r) {
        return payroll.createRun(r.payPeriodId(), r.runType());
    }

    /** Settlement for one separated employee. Needs the regular period containing the separation date to be locked. */
    @PostMapping("/final-pay")
    @ResponseStatus(HttpStatus.CREATED)
    public PayrollRun createFinalPay(@jakarta.validation.Valid @RequestBody FinalPayRequest r) {
        return payroll.createFinalPayRun(r.payPeriodId(), r.employeeId());
    }

    @PostMapping("/runs/{id}/compute")
    public PayrollRun compute(@PathVariable UUID id) {
        return payroll.compute(id);
    }

    @PostMapping("/runs/{id}/review")
    public PayrollRun review(@PathVariable UUID id) {
        return payroll.review(id);
    }

    @PostMapping("/runs/{id}/approve")
    public PayrollRun approve(@PathVariable UUID id, @AuthenticationPrincipal HrmUserPrincipal user) {
        return payroll.approve(id, user.id());
    }

    @PostMapping("/runs/{id}/lock")
    public PayrollRun lock(@PathVariable UUID id) {
        return payroll.lock(id);
    }

    @PostMapping("/runs/{id}/pay")
    public PayrollRun pay(@PathVariable UUID id) {
        return payroll.markPaid(id);
    }

    @GetMapping("/runs/{id}/employees")
    public List<PayrollEmployee> employees(@PathVariable UUID id) {
        return payrollEmployees.findByRunId(id);
    }

    @GetMapping("/runs/{id}/lines")
    public List<PayrollLine> lines(@PathVariable UUID id) {
        return lines.findByRunId(id);
    }

    @PostMapping("/runs/{id}/journal-entry")
    @ResponseStatus(HttpStatus.CREATED)
    public JournalEntry journal(@PathVariable UUID id) {
        return journals.generate(id);
    }

    @PostMapping("/journal-entries/{id}/post")
    public JournalEntry post(@PathVariable UUID id) {
        return journals.post(id);
    }

    @PostMapping("/runs/{id}/bank-file")
    @ResponseStatus(HttpStatus.CREATED)
    public BankFileService.Result bankFile(@PathVariable UUID id, @RequestBody BankFileRequest r) {
        return bankFiles.generate(id, r.bankTemplateId());
    }

    @GetMapping("/bank-files/{id}/download")
    public ResponseEntity<String> download(@PathVariable UUID id) {
        BankFile f = bankFileRepo.findById(id).orElseThrow(() -> new NotFoundException("Bank file", id));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + f.getFileName() + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(f.getContent());
    }

    @PostMapping("/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    public Adjustment createAdjustment(@RequestBody AdjustmentRequest r) {
        return adjustments.create(r.employeeId(), r.itemCode(), r.description(), r.category(), r.amount(),
                r.taxable() == null || r.taxable(), r.reason(), r.sourcePeriodId(), r.targetPeriodId(), r.requestedBy());
    }

    @PostMapping("/adjustments/{id}/approve")
    public Adjustment approveAdjustment(@PathVariable UUID id, @AuthenticationPrincipal HrmUserPrincipal user) {
        return adjustments.approve(id, user.id());
    }

    @PostMapping("/adjustments/{id}/reject")
    public Adjustment rejectAdjustment(@PathVariable UUID id, @AuthenticationPrincipal HrmUserPrincipal user) {
        return adjustments.reject(id, user.id());
    }
}
