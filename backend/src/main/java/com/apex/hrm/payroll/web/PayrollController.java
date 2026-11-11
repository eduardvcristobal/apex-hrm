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
import com.apex.hrm.payroll.repo.PayrollRunRepository;
import com.apex.hrm.payroll.service.AdjustmentService;
import com.apex.hrm.payroll.service.BankFileService;
import com.apex.hrm.payroll.service.JournalService;
import com.apex.hrm.payroll.service.PayrollService;
import com.apex.hrm.personnel.domain.Employee;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import com.apex.hrm.org.domain.Company;
import com.apex.hrm.org.repo.CompanyRepository;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.service.AuditService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import org.springframework.web.bind.annotation.PutMapping;
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

    public record BankFileSummary(UUID id, UUID runId, UUID bankTemplateId, String fileName, UUID recoverySourceFileId,
                                  int recordCount, BigDecimal totalAmount) {}
    public record TaxProfile(String tin, Boolean minimumWageEarner) {}
    public record PriorEmployerProfile(String priorEmployerName, String priorEmployerTin, String priorEmployerAddress,
                                      BigDecimal priorEmployerCompensation, BigDecimal priorEmployerTaxableCompensation,
                                      BigDecimal priorEmployerNonTaxableCompensation, BigDecimal priorEmployerTaxWithheld,
                                      Boolean priorEmployerCertificateReceived) {}
    public record AnnualCertificateRow(UUID employeeId, String employeeNo, String employeeName, String employeeTin,
                                       String employeeAddress, LocalDate birthDate, LocalDate hireDate,
                                       LocalDate separationDate, String employerName, String employerTin,
                                       String employerAddress, String employerRdo, int taxableRuns,
                                       BigDecimal grossCompensation, BigDecimal nonTaxableCompensation,
                                       BigDecimal taxableCompensation, BigDecimal withholdingTax,
                                       String priorEmployerName, BigDecimal priorEmployerCompensation,
                                       BigDecimal priorEmployerTaxableCompensation, BigDecimal priorEmployerNonTaxableCompensation,
                                       BigDecimal priorEmployerTaxWithheld,
                                       List<String> missingFields, boolean readyForReview) {}

    private final PayrollService payroll;
    private final JournalService journals;
    private final BankFileService bankFiles;
    private final AdjustmentService adjustments;
    private final PayrollEmployeeRepository payrollEmployees;
    private final PayrollLineRepository lines;
    private final BankFileRepository bankFileRepo;
    private final EmployeeRepository employees;
    private final CompanyRepository companies;
    private final AuditService audit;
    private final PayrollRunRepository runs;

    public PayrollController(PayrollService payroll, JournalService journals, BankFileService bankFiles,
                             AdjustmentService adjustments, PayrollEmployeeRepository payrollEmployees,
                             PayrollLineRepository lines, BankFileRepository bankFileRepo,
                             EmployeeRepository employees, CompanyRepository companies, AuditService audit,
                             PayrollRunRepository runs) {
        this.payroll = payroll;
        this.journals = journals;
        this.bankFiles = bankFiles;
        this.adjustments = adjustments;
        this.payrollEmployees = payrollEmployees;
        this.lines = lines;
        this.bankFileRepo = bankFileRepo;
        this.employees = employees;
        this.companies = companies;
        this.audit = audit;
        this.runs = runs;
    }

    /** Minimal employee directory for payroll staff; avoids exposing private employee fields. */
    public record EmployeeSummary(UUID id, String employeeNo, String firstName, String lastName, String status,
                                  LocalDate separationDate, Boolean minimumWageEarner) {
        static EmployeeSummary from(Employee employee) {
            return new EmployeeSummary(employee.getId(), employee.getEmployeeNo(), employee.getFirstName(),
                    employee.getLastName(), employee.getStatus(), employee.getSeparationDate(), employee.getMinimumWageEarner());
        }
    }

    @GetMapping("/employees")
    public Page<EmployeeSummary> employeeDirectory(Pageable pageable) {
        return employees.findAll(pageable).map(EmployeeSummary::from);
    }

    @GetMapping("/employees/{employeeId}/tax-profile")
    public TaxProfile taxProfile(@PathVariable UUID employeeId) {
        Employee employee = employees.findById(employeeId).orElseThrow(() -> new NotFoundException("Employee", employeeId));
        return new TaxProfile(employee.getTin(), employee.getMinimumWageEarner());
    }

    @PutMapping("/employees/{employeeId}/tax-profile")
    public TaxProfile updateTaxProfile(@PathVariable UUID employeeId, @RequestBody TaxProfile input,
                                       @AuthenticationPrincipal HrmUserPrincipal actor) {
        Employee employee = employees.findById(employeeId).orElseThrow(() -> new NotFoundException("Employee", employeeId));
        if (input.minimumWageEarner() == null) {
            throw new com.apex.hrm.common.BusinessException("minimumWageEarner must be explicitly set");
        }
        String tin = input.tin() == null || input.tin().isBlank() ? null : input.tin().trim();
        String tinDigits = tin == null ? "" : tin.replace("-", "");
        if (tin != null && (!tin.matches("[0-9-]+") || (tinDigits.length() != 9 && tinDigits.length() != 12))) {
            throw new com.apex.hrm.common.BusinessException("TIN must contain 9 to 12 digits, optionally separated by hyphens");
        }
        employee.setTin(tin);
        employee.setMinimumWageEarner(input.minimumWageEarner());
        employees.save(employee);
        audit.log("EmployeeTaxProfile", employeeId, "UPDATE", "TIN and minimum-wage-earner tax status updated by " + actor.id());
        return new TaxProfile(employee.getTin(), employee.getMinimumWageEarner());
    }

    @GetMapping("/employees/{employeeId}/prior-employer")
    public PriorEmployerProfile priorEmployer(@PathVariable UUID employeeId) {
        Employee employee = employees.findById(employeeId).orElseThrow(() -> new NotFoundException("Employee", employeeId));
        return priorProfile(employee);
    }

    @PutMapping("/employees/{employeeId}/prior-employer")
    public PriorEmployerProfile updatePriorEmployer(@PathVariable UUID employeeId, @RequestBody PriorEmployerProfile input,
                                                   @AuthenticationPrincipal HrmUserPrincipal actor) {
        Employee employee = employees.findById(employeeId).orElseThrow(() -> new NotFoundException("Employee", employeeId));
        BigDecimal compensation = amount(input.priorEmployerCompensation());
        BigDecimal taxable = amount(input.priorEmployerTaxableCompensation());
        BigDecimal nonTaxable = amount(input.priorEmployerNonTaxableCompensation());
        BigDecimal withheld = amount(input.priorEmployerTaxWithheld());
        if (taxable.add(nonTaxable).compareTo(compensation) > 0) throw new com.apex.hrm.common.BusinessException("Taxable and non-taxable compensation cannot exceed prior-employer compensation");
        String tin = input.priorEmployerTin() == null || input.priorEmployerTin().isBlank() ? null : input.priorEmployerTin().trim();
        if (tin != null && !tin.matches("[0-9-]{9,16}")) throw new com.apex.hrm.common.BusinessException("Prior-employer TIN must be 9 to 12 digits, optionally separated by hyphens");
        boolean hasEmployer = !blank(input.priorEmployerName()) || tin != null || !blank(input.priorEmployerAddress())
                || compensation.signum() > 0 || taxable.signum() > 0 || nonTaxable.signum() > 0 || withheld.signum() > 0;
        if (hasEmployer && !Boolean.TRUE.equals(input.priorEmployerCertificateReceived()))
            throw new com.apex.hrm.common.BusinessException("Confirm receipt of the previous employer’s BIR Form 2316 before including prior-employer amounts");
        employee.setPriorEmployerName(blank(input.priorEmployerName()) ? null : input.priorEmployerName().trim());
        employee.setPriorEmployerTin(tin);
        employee.setPriorEmployerAddress(blank(input.priorEmployerAddress()) ? null : input.priorEmployerAddress().trim());
        employee.setPriorEmployerCompensation(compensation);
        employee.setPriorEmployerTaxableCompensation(taxable);
        employee.setPriorEmployerNonTaxableCompensation(nonTaxable);
        employee.setPriorEmployerTaxWithheld(withheld);
        employee.setPriorEmployerCertificateReceived(Boolean.TRUE.equals(input.priorEmployerCertificateReceived()));
        employees.save(employee);
        audit.log("EmployeePriorEmployerTax", employeeId, "UPDATE", "Prior-employer annual tax information updated by " + actor.id());
        return priorProfile(employee);
    }

    private static BigDecimal amount(BigDecimal value) {
        if (value == null) return BigDecimal.ZERO;
        if (value.signum() < 0 || value.scale() > 2) throw new com.apex.hrm.common.BusinessException("Prior-employer amounts must be non-negative and limited to cents");
        return value;
    }

    private static PriorEmployerProfile priorProfile(Employee employee) {
        return new PriorEmployerProfile(employee.getPriorEmployerName(), employee.getPriorEmployerTin(), employee.getPriorEmployerAddress(),
                employee.getPriorEmployerCompensation(), employee.getPriorEmployerTaxableCompensation(),
                employee.getPriorEmployerNonTaxableCompensation(), employee.getPriorEmployerTaxWithheld(),
                employee.getPriorEmployerCertificateReceived());
    }

    @GetMapping("/reports/annual-tax-certificate")
    public Object annualTaxCertificate(@org.springframework.web.bind.annotation.RequestParam int year,
                                      @org.springframework.web.bind.annotation.RequestParam(required = false) UUID employeeId) {
        if (year < 2000 || year > LocalDate.now().getYear() + 1) {
            throw new com.apex.hrm.common.BusinessException("Choose a valid tax year");
        }
        LocalDate from = LocalDate.of(year, 1, 1), to = LocalDate.of(year, 12, 31);
        var stream = employees.findAll(Sort.by(Sort.Direction.ASC, "lastName", "firstName", "employeeNo")).stream()
                .filter(employee -> employee.getHireDate() == null || !employee.getHireDate().isAfter(to))
                .filter(employee -> employee.getSeparationDate() == null || !employee.getSeparationDate().isBefore(from));
        if (employeeId != null) {
            Employee employee = employees.findById(employeeId).orElseThrow(() -> new NotFoundException("Employee", employeeId));
            if (employee.getHireDate() != null && employee.getHireDate().isAfter(to)
                    || employee.getSeparationDate() != null && employee.getSeparationDate().isBefore(from))
                throw new com.apex.hrm.common.BusinessException("Employee has no employment overlap with this tax year");
            return certificateRow(employee, from, to);
        }
        return stream.map(employee -> certificateRow(employee, from, to)).toList();
    }

    @GetMapping(value = "/reports/annual-tax-certificate.csv", produces = "text/csv")
    public ResponseEntity<byte[]> annualTaxCertificateCsv(@org.springframework.web.bind.annotation.RequestParam int year,
                                                          @org.springframework.web.bind.annotation.RequestParam(required = false) UUID employeeId) {
        Object certificate = annualTaxCertificate(year, employeeId);
        List<AnnualCertificateRow> rows = certificate instanceof AnnualCertificateRow row ? List.of(row)
                : (List<AnnualCertificateRow>) certificate;
        StringBuilder csv = new StringBuilder("Tax year,Employee No,Employee Name,Employee TIN,Employee Address,Employer Name,Employer TIN,Employer Address,RDO,Taxable Payroll Runs,Regular Gross,Non-taxable Compensation,Taxable Compensation,Withholding Tax,Prior Employer,Prior Employer Gross,Prior Employer Taxable,Prior Employer Non-taxable,Prior Employer Tax Withheld,Missing Fields,Ready for review\r\n");
        for (AnnualCertificateRow row : rows) {
            csv.append(year).append(',').append(csv(row.employeeNo())).append(',').append(csv(row.employeeName())).append(',')
                    .append(csv(row.employeeTin())).append(',').append(csv(row.employeeAddress())).append(',')
                    .append(csv(row.employerName())).append(',').append(csv(row.employerTin())).append(',')
                    .append(csv(row.employerAddress())).append(',').append(csv(row.employerRdo())).append(',')
                    .append(row.taxableRuns()).append(',').append(row.grossCompensation()).append(',')
                    .append(row.nonTaxableCompensation()).append(',').append(row.taxableCompensation()).append(',')
                    .append(row.withholdingTax()).append(',').append(csv(row.priorEmployerName())).append(',')
                    .append(row.priorEmployerCompensation()).append(',').append(row.priorEmployerTaxableCompensation()).append(',')
                    .append(row.priorEmployerNonTaxableCompensation()).append(',').append(row.priorEmployerTaxWithheld()).append(',')
                    .append(csv(String.join("; ", row.missingFields())))
                    .append(',').append(row.readyForReview()).append("\r\n");
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=annual-tax-certificate-" + year + ".csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private AnnualCertificateRow certificateRow(Employee employee, LocalDate from, LocalDate to) {
        Company employer = companies.findById(employee.getCompanyId()).orElse(null);
        List<PayrollRun> employeeRuns = runs.findFinalizedCompensationBetweenForCompany(from, to, employee.getCompanyId()).stream()
                .filter(run -> payrollEmployees.findByRunId(run.getId()).stream()
                        .anyMatch(row -> employee.getId().equals(row.getEmployeeId()))).toList();
        BigDecimal gross = BigDecimal.ZERO, taxable = BigDecimal.ZERO, nonTaxable = BigDecimal.ZERO, withheld = BigDecimal.ZERO;
        for (PayrollRun run : employeeRuns) {
            for (PayrollLine line : lines.findByRunId(run.getId())) {
                if (!employee.getId().equals(line.getEmployeeId())) continue;
                if ("EARNING".equals(line.getCategory()) && !"TAX_REFUND".equals(line.getItemCode())) {
                    gross = gross.add(line.getAmount());
                    if (Boolean.TRUE.equals(line.getTaxable())) taxable = taxable.add(line.getAmount()); else nonTaxable = nonTaxable.add(line.getAmount());
                }
                if ("WTAX".equals(line.getItemCode())) withheld = withheld.add(line.getAmount());
                if ("TAX_REFUND".equals(line.getItemCode())) withheld = withheld.subtract(line.getAmount());
            }
        }
        int finalizedRuns = employeeRuns.size();
        BigDecimal priorGross = employee.getPriorEmployerCompensation() == null ? BigDecimal.ZERO : employee.getPriorEmployerCompensation();
        BigDecimal priorTaxable = employee.getPriorEmployerTaxableCompensation() == null ? BigDecimal.ZERO : employee.getPriorEmployerTaxableCompensation();
        BigDecimal priorNonTaxable = employee.getPriorEmployerNonTaxableCompensation() == null ? BigDecimal.ZERO : employee.getPriorEmployerNonTaxableCompensation();
        BigDecimal priorWithheld = employee.getPriorEmployerTaxWithheld() == null ? BigDecimal.ZERO : employee.getPriorEmployerTaxWithheld();
        boolean hasPriorEmployer = !blank(employee.getPriorEmployerName()) || priorGross.signum() > 0 || priorTaxable.signum() > 0
                || priorNonTaxable.signum() > 0 || priorWithheld.signum() > 0;
        List<String> missing = new ArrayList<>();
        if (blank(employee.getTin())) missing.add("Employee TIN");
        if (blank(employee.getAddress())) missing.add("Employee address");
        if (employee.getBirthDate() == null) missing.add("Birth date");
        if (employer == null || blank(employer.getLegalName())) missing.add("Employer legal name");
        if (employer == null || blank(employer.getTin())) missing.add("Employer TIN");
        if (employer == null || blank(employer.getAddress())) missing.add("Employer address");
        if (employer == null || blank(employer.getRdoCode())) missing.add("Employer RDO");
        if (employeeRuns.stream().noneMatch(run -> "REGULAR".equals(run.getRunType())) && !hasPriorEmployer) missing.add("No finalized regular payroll runs");
        if (hasPriorEmployer && !Boolean.TRUE.equals(employee.getPriorEmployerCertificateReceived())) missing.add("Prior employer Form 2316 confirmation");
        if (hasPriorEmployer && blank(employee.getPriorEmployerTin())) missing.add("Prior employer TIN");
        if (hasPriorEmployer && blank(employee.getPriorEmployerAddress())) missing.add("Prior employer address");
        if (hasPriorEmployer && employee.getPriorEmployerCompensation() != null
                && employee.getPriorEmployerTaxableCompensation() != null && employee.getPriorEmployerNonTaxableCompensation() != null
                && employee.getPriorEmployerTaxableCompensation().add(employee.getPriorEmployerNonTaxableCompensation())
                .compareTo(employee.getPriorEmployerCompensation()) > 0) missing.add("Prior employer compensation reconciliation");
        return new AnnualCertificateRow(employee.getId(), employee.getEmployeeNo(),
                java.util.stream.Stream.of(employee.getLastName(), employee.getFirstName(), employee.getMiddleName(), employee.getSuffix())
                        .filter(value -> value != null && !value.isBlank()).collect(java.util.stream.Collectors.joining(" ")),
                employee.getTin(), employee.getAddress(), employee.getBirthDate(), employee.getHireDate(),
                employee.getSeparationDate(), employer == null ? null : employer.getLegalName(),
                employer == null ? null : employer.getTin(), employer == null ? null : employer.getAddress(),
                employer == null ? null : employer.getRdoCode(), finalizedRuns, gross.add(priorGross), nonTaxable.add(priorNonTaxable), taxable.add(priorTaxable),
                withheld.add(priorWithheld), employee.getPriorEmployerName(), priorGross, priorTaxable, priorNonTaxable,
                priorWithheld, List.copyOf(missing), missing.isEmpty());
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static String csv(String value) {
        if (value == null) return "\"\"";
        return "\"" + value.replace("\"", "\"\"") + "\"";
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

    @GetMapping("/runs/{id}/bank-files")
    public List<BankFileSummary> bankFiles(@PathVariable UUID id) {
        return bankFiles.listForRun(id).stream().map(file -> new BankFileSummary(file.getId(), file.getRunId(),
                file.getBankTemplateId(), file.getFileName(), file.getRecoverySourceFileId(), file.getRecordCount(),
                file.getTotalAmount())).toList();
    }

    @GetMapping("/bank-files/{id}/download")
    public ResponseEntity<String> download(@PathVariable UUID id) {
        BankFile f = bankFiles.getFile(id);
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
