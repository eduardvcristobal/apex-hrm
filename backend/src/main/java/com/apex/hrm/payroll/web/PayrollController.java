package com.apex.hrm.payroll.web;

import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.payroll.domain.Adjustment;
import com.apex.hrm.payroll.domain.BankFile;
import com.apex.hrm.payroll.domain.JournalEntry;
import com.apex.hrm.payroll.domain.PayrollEmployee;
import com.apex.hrm.payroll.domain.PayrollLine;
import com.apex.hrm.payroll.domain.PayrollRun;
import com.apex.hrm.payroll.domain.EmployeePeraTaxCredit;
import com.apex.hrm.payroll.repo.BankFileRepository;
import com.apex.hrm.payroll.repo.PayrollEmployeeRepository;
import com.apex.hrm.payroll.repo.PayrollLineRepository;
import com.apex.hrm.payroll.repo.PayrollRunRepository;
import com.apex.hrm.payroll.repo.EmployeePeraTaxCreditRepository;
import com.apex.hrm.payroll.repo.WithholdingTaxBracketRepository;
import com.apex.hrm.payroll.domain.WithholdingTaxBracket;
import com.apex.hrm.payroll.engine.WithholdingTaxCalculator;
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
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.jdbc.core.JdbcTemplate;
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
                                    Boolean taxable, String reason, UUID sourcePeriodId, UUID targetPeriodId,
                                    String incomeClass) {
    }

    public record BankFileSummary(UUID id, UUID runId, UUID bankTemplateId, String fileName, UUID recoverySourceFileId,
                                  int recordCount, BigDecimal totalAmount) {}
    public record TaxProfile(String tin, Boolean minimumWageEarner, String employeeRdoCode, String registeredZipCode,
                             String localHomeAddress, String localHomeZipCode, String foreignAddress,
                             BigDecimal statutoryMinimumWageDaily, BigDecimal statutoryMinimumWageMonthly,
                             String mobile, String branchCode, String regionNum, String nationality,
                             String employmentStatus, String reasonSeparation, String substitutedFiling,
                             Integer minimumWageFactorUsed) {}
    public record EmployerTaxProfile(UUID companyId, String legalName, String tin, String branchCode, String address, String rdoCode,
                                    String zipCode, String employerType, Boolean mainEmployer) {}
    public record PeraTaxCreditProfile(UUID employeeId, int taxYear, BigDecimal qualifiedContributions,
                                       boolean overseasFilipino, BigDecimal taxCreditAmount,
                                       String certificateNumber, boolean certificateReceived) {}
    public record PriorEmployerProfile(String priorEmployerName, String priorEmployerTin, String priorEmployerAddress,
                                      String priorEmployerRdoCode, String priorEmployerZipCode,
                                      BigDecimal priorEmployerCompensation, BigDecimal priorEmployerTaxableCompensation,
                                      BigDecimal priorEmployerNonTaxableCompensation, BigDecimal priorEmployerTaxWithheld,
                                      Boolean priorEmployerCertificateReceived,
                                      BigDecimal priorNontaxBasicSmw, BigDecimal priorNontaxHolidayPay,
                                      BigDecimal priorNontaxOvertimePay, BigDecimal priorNontaxNightDiff,
                                      BigDecimal priorNontaxHazardPay, BigDecimal priorNontax13thMonth,
                                      BigDecimal priorNontaxDeminimis, BigDecimal priorNontaxSssEtc,
                                      BigDecimal priorNontaxSalaries, BigDecimal priorTaxableBasicSalary,
                                      BigDecimal priorTaxable13thMonth, BigDecimal priorTaxableSalaries) {}
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
    public record Bir2316Draft(AnnualCertificateRow summary, Map<String,BigDecimal> compensationItems,
                               BigDecimal taxableCompensationPresentEmployer,
                               BigDecimal taxableCompensationPreviousEmployer,
                               BigDecimal grossTaxableCompensation, BigDecimal taxDue,
                               BigDecimal presentEmployerTaxWithheld, BigDecimal previousEmployerTaxWithheld,
                               BigDecimal totalTaxesWithheld, BigDecimal peraTaxCredit,
                               boolean minimumWageEarner, String formVersion, boolean draft,
                               List<String> manualCompletionFields, TaxProfile employeeTaxProfile,
                               EmployerTaxProfile employerTaxProfile, PriorEmployerProfile priorEmployerTaxProfile,
                               PeraTaxCreditProfile peraTaxCreditProfile,
                               String reviewStatus) {}
    public record ReviewDecision(String decision, String note) {}
    public record ReviewStatus(UUID id, UUID employeeId, String employeeNo, String employeeName, int taxYear,
                               String status, UUID submittedBy, java.time.Instant submittedAt,
                               UUID decidedBy, java.time.Instant decidedAt, String decisionNote, boolean stale) {}
    public record AlphalistRow(int sequence, String schedule, String employeeNo, String employeeName,
                               String tin, String employmentFrom, String employmentTo,
                               BigDecimal presentEmployerGross, BigDecimal presentEmployerNonTaxable,
                               BigDecimal presentEmployerTaxable, BigDecimal previousEmployerGross,
                               BigDecimal previousEmployerNonTaxable, BigDecimal previousEmployerTaxable,
                               BigDecimal combinedTaxable, BigDecimal annualTaxDue,
                               BigDecimal presentEmployerTaxWithheld, BigDecimal previousEmployerTaxWithheld,
                               BigDecimal peraTaxCredit, BigDecimal adjustedTaxWithheld,
                               String form2316Status, int finalizedPayrollRuns, List<String> exceptions) {}
    public record AlphalistReport(int taxYear, UUID companyId, String employerName, String employerTin,
                                  int employeeCount, int schedule1Count, int schedule2Count,
                                  BigDecimal presentEmployerGross, BigDecimal presentEmployerNonTaxable,
                                  BigDecimal presentEmployerTaxable, BigDecimal previousEmployerTaxable,
                                  BigDecimal totalTaxWithheldAsAdjusted, long approved2316Count,
                                  long exceptionCount, List<AlphalistRow> rows,
                                  String artifactType, String filingNotice) {}

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
    private final WithholdingTaxBracketRepository taxBrackets;
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantSql;
    private final EmployeePeraTaxCreditRepository peraCredits;

    public PayrollController(PayrollService payroll, JournalService journals, BankFileService bankFiles,
                             AdjustmentService adjustments, PayrollEmployeeRepository payrollEmployees,
                             PayrollLineRepository lines, BankFileRepository bankFileRepo,
                             EmployeeRepository employees, CompanyRepository companies, AuditService audit,
                             PayrollRunRepository runs, WithholdingTaxBracketRepository taxBrackets, JdbcTemplate jdbc,
                             TenantJdbcExecutor tenantSql, EmployeePeraTaxCreditRepository peraCredits) {
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
        this.taxBrackets = taxBrackets;
        this.jdbc = jdbc;
        this.tenantSql = tenantSql;
        this.peraCredits = peraCredits;
    }

    /** Minimal employee directory for payroll staff; avoids exposing private employee fields. */
    public record EmployeeSummary(UUID id, UUID companyId, String employeeNo, String firstName, String lastName, String status,
                                  LocalDate separationDate, Boolean minimumWageEarner) {
        static EmployeeSummary from(Employee employee) {
            return new EmployeeSummary(employee.getId(), employee.getCompanyId(), employee.getEmployeeNo(), employee.getFirstName(),
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
        return taxProfile(employee);
    }

    @PutMapping("/employees/{employeeId}/tax-profile")
    public TaxProfile updateTaxProfile(@PathVariable UUID employeeId, @RequestBody TaxProfile input,
                                       @AuthenticationPrincipal HrmUserPrincipal actor) {
        Employee employee = employees.findById(employeeId).orElseThrow(() -> new NotFoundException("Employee", employeeId));
        if (input.minimumWageEarner() == null) {
            throw new com.apex.hrm.common.BusinessException("minimumWageEarner must be explicitly set");
        }
        String tin = input.tin() == null || input.tin().isBlank() ? null : input.tin().trim();
        validateTin(tin,"TIN");
        validateZip(input.registeredZipCode()); validateZip(input.localHomeZipCode()); validateRdo(input.employeeRdoCode());
        nonNegative(input.statutoryMinimumWageDaily(), "Daily minimum wage");
        nonNegative(input.statutoryMinimumWageMonthly(), "Monthly minimum wage");
        employee.setTin(tin);
        employee.setMinimumWageEarner(input.minimumWageEarner());
        employee.setEmployeeRdoCode(clean(input.employeeRdoCode()));
        employee.setRegisteredZipCode(clean(input.registeredZipCode()));
        employee.setLocalHomeAddress(clean(input.localHomeAddress()));
        employee.setLocalHomeZipCode(clean(input.localHomeZipCode()));
        employee.setForeignAddress(clean(input.foreignAddress()));
        employee.setStatutoryMinimumWageDaily(input.statutoryMinimumWageDaily());
        employee.setStatutoryMinimumWageMonthly(input.statutoryMinimumWageMonthly());
        employee.setMobile(clean(input.mobile()));
        validateBranchCode(input.branchCode()); validateRegionNum(input.regionNum());
        if(clean(input.employmentStatus())!=null&&!Set.of("R","C","CP","S","P","AL").contains(input.employmentStatus().trim().toUpperCase()))
            throw new com.apex.hrm.common.BusinessException("BIR employment status must be R, C, CP, S, P, or AL");
        if(clean(input.reasonSeparation())!=null&&!Set.of("NA","T","TR","R","D").contains(input.reasonSeparation().trim().toUpperCase()))
            throw new com.apex.hrm.common.BusinessException("BIR separation reason must be NA, T, TR, R, or D");
        String substituted=clean(input.substitutedFiling());
        if(substituted!=null&&!Set.of("Y","N").contains(substituted.trim().toUpperCase()))
            throw new com.apex.hrm.common.BusinessException("Substituted filing must be Y or N");
        if(input.minimumWageFactorUsed()!=null&&(input.minimumWageFactorUsed()<1||input.minimumWageFactorUsed()>366))
            throw new com.apex.hrm.common.BusinessException("Minimum-wage factor used must be between 1 and 366 days");
        employee.setBranchCode(clean(input.branchCode())); employee.setRegionNum(clean(input.regionNum()));
        employee.setNationality(clean(input.nationality()));
        employee.setBirEmploymentStatus(clean(input.employmentStatus())==null?null:input.employmentStatus().trim().toUpperCase());
        employee.setBirReasonSeparation(clean(input.reasonSeparation())==null?null:input.reasonSeparation().trim().toUpperCase());
        employee.setBirSubstitutedFiling(substituted==null?null:substituted.trim().toUpperCase());
        employee.setMinimumWageFactorUsed(input.minimumWageFactorUsed());
        employees.save(employee);
        audit.log("EmployeeTaxProfile", employeeId, "UPDATE", "Employee 2316 tax profile updated by " + actor.id());
        return taxProfile(employee);
    }

    @GetMapping("/companies/{companyId}/tax-profile")
    public EmployerTaxProfile employerTaxProfile(@PathVariable UUID companyId) {
        return employerTaxProfile(companies.findById(companyId).orElseThrow(() -> new NotFoundException("Company", companyId)));
    }

    @PutMapping("/companies/{companyId}/tax-profile")
    public EmployerTaxProfile updateEmployerTaxProfile(@PathVariable UUID companyId, @RequestBody EmployerTaxProfile input,
                                                       @AuthenticationPrincipal HrmUserPrincipal actor) {
        Company company=companies.findById(companyId).orElseThrow(() -> new NotFoundException("Company", companyId));
        validateZip(input.zipCode()); validateRdo(input.rdoCode()); validateBranchCode(input.branchCode());
        validateTin(clean(input.tin()),"Employer TIN");
        if (input.mainEmployer()==null) throw new com.apex.hrm.common.BusinessException("mainEmployer must be explicitly set");
        if (clean(input.employerType())!=null && !Set.of("PRIVATE","GOVERNMENT","NONPROFIT","OTHER").contains(input.employerType().trim().toUpperCase()))
            throw new com.apex.hrm.common.BusinessException("Employer type must be private, government, nonprofit, or other");
        company.setTin(clean(input.tin())); company.setBranchCode(clean(input.branchCode())); company.setAddress(clean(input.address())); company.setRdoCode(clean(input.rdoCode()));
        company.setZipCode(clean(input.zipCode())); company.setEmployerType(clean(input.employerType())==null?null:input.employerType().trim().toUpperCase());
        company.setMainEmployer(input.mainEmployer()); companies.save(company);
        audit.log("CompanyTaxProfile", companyId, "UPDATE", "Employer 2316 tax profile updated by " + actor.id());
        return employerTaxProfile(company);
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
        BigDecimal[] priorParts={amount(input.priorNontaxBasicSmw()),amount(input.priorNontaxHolidayPay()),amount(input.priorNontaxOvertimePay()),
                amount(input.priorNontaxNightDiff()),amount(input.priorNontaxHazardPay()),amount(input.priorNontax13thMonth()),
                amount(input.priorNontaxDeminimis()),amount(input.priorNontaxSssEtc()),amount(input.priorNontaxSalaries())};
        BigDecimal priorNonTaxBreakdown=java.util.Arrays.stream(priorParts).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal priorTaxBreakdown=amount(input.priorTaxableBasicSalary()).add(amount(input.priorTaxable13thMonth())).add(amount(input.priorTaxableSalaries()));
        if(priorNonTaxBreakdown.compareTo(nonTaxable)!=0||priorTaxBreakdown.compareTo(taxable)!=0)
            throw new com.apex.hrm.common.BusinessException("Break down prior-employer Form 2316 compensation so non-taxable and taxable categories each reconcile to their totals");
        if (taxable.add(nonTaxable).compareTo(compensation) > 0) throw new com.apex.hrm.common.BusinessException("Taxable and non-taxable compensation cannot exceed prior-employer compensation");
        String tin = input.priorEmployerTin() == null || input.priorEmployerTin().isBlank() ? null : input.priorEmployerTin().trim();
        validateTin(tin,"Prior-employer TIN");
        boolean hasEmployer = !blank(input.priorEmployerName()) || tin != null || !blank(input.priorEmployerAddress())
                || compensation.signum() > 0 || taxable.signum() > 0 || nonTaxable.signum() > 0 || withheld.signum() > 0;
        if (hasEmployer && !Boolean.TRUE.equals(input.priorEmployerCertificateReceived()))
            throw new com.apex.hrm.common.BusinessException("Confirm receipt of the previous employer’s BIR Form 2316 before including prior-employer amounts");
        employee.setPriorEmployerName(blank(input.priorEmployerName()) ? null : input.priorEmployerName().trim());
        employee.setPriorEmployerTin(tin);
        employee.setPriorEmployerAddress(blank(input.priorEmployerAddress()) ? null : input.priorEmployerAddress().trim());
        validateZip(input.priorEmployerZipCode()); validateRdo(input.priorEmployerRdoCode());
        employee.setPriorEmployerZipCode(clean(input.priorEmployerZipCode()));
        employee.setPriorEmployerRdoCode(clean(input.priorEmployerRdoCode()));
        employee.setPriorEmployerCompensation(compensation);
        employee.setPriorEmployerTaxableCompensation(taxable);
        employee.setPriorEmployerNonTaxableCompensation(nonTaxable);
        employee.setPriorEmployerTaxWithheld(withheld);
        employee.setPriorEmployerCertificateReceived(Boolean.TRUE.equals(input.priorEmployerCertificateReceived()));
        employee.setPriorNontaxBasicSmw(priorParts[0]); employee.setPriorNontaxHolidayPay(priorParts[1]);
        employee.setPriorNontaxOvertimePay(priorParts[2]); employee.setPriorNontaxNightDiff(priorParts[3]);
        employee.setPriorNontaxHazardPay(priorParts[4]); employee.setPriorNontax13thMonth(priorParts[5]);
        employee.setPriorNontaxDeminimis(priorParts[6]); employee.setPriorNontaxSssEtc(priorParts[7]);
        employee.setPriorNontaxSalaries(priorParts[8]); employee.setPriorTaxableBasicSalary(amount(input.priorTaxableBasicSalary()));
        employee.setPriorTaxable13thMonth(amount(input.priorTaxable13thMonth())); employee.setPriorTaxableSalaries(amount(input.priorTaxableSalaries()));
        employees.save(employee);
        audit.log("EmployeePriorEmployerTax", employeeId, "UPDATE", "Prior-employer annual tax information updated by " + actor.id());
        return priorProfile(employee);
    }

    @GetMapping("/employees/{employeeId}/pera-tax-credit")
    public PeraTaxCreditProfile peraTaxCredit(@PathVariable UUID employeeId,@org.springframework.web.bind.annotation.RequestParam int taxYear) {
        Employee employee=employees.findById(employeeId).orElseThrow(()->new NotFoundException("Employee",employeeId));
        validateTaxYear(taxYear);
        return peraCredits.findByEmployeeIdAndTaxYear(employeeId,taxYear).map(PayrollController::peraProfile)
                .orElse(new PeraTaxCreditProfile(employeeId,taxYear,BigDecimal.ZERO,false,BigDecimal.ZERO,null,false));
    }

    @PutMapping("/employees/{employeeId}/pera-tax-credit")
    public PeraTaxCreditProfile updatePeraTaxCredit(@PathVariable UUID employeeId,@org.springframework.web.bind.annotation.RequestParam int taxYear,
                                                    @RequestBody PeraTaxCreditProfile input,@AuthenticationPrincipal HrmUserPrincipal actor) {
        Employee employee=employees.findById(employeeId).orElseThrow(()->new NotFoundException("Employee",employeeId));
        validateTaxYear(taxYear);
        if(input.taxYear()!=taxYear||!employeeId.equals(input.employeeId()))throw new com.apex.hrm.common.BusinessException("PERA tax credit employee and year must match the selected profile");
        BigDecimal contributions=amount(input.qualifiedContributions()),credit=amount(input.taxCreditAmount());
        BigDecimal maxContribution=input.overseasFilipino()?new BigDecimal("200000"):new BigDecimal("100000");
        if(contributions.compareTo(maxContribution)>0)throw new com.apex.hrm.common.BusinessException("Qualified PERA contributions exceed the annual statutory limit for this contributor type");
        if(credit.compareTo(contributions.multiply(new BigDecimal("0.05")))>0)throw new com.apex.hrm.common.BusinessException("PERA tax credit cannot exceed five percent of qualified annual contributions");
        String certificate=clean(input.certificateNumber());
        if(credit.signum()>0&&(!input.certificateReceived()||certificate==null))throw new com.apex.hrm.common.BusinessException("Record a received PERA Tax Credit Certificate number before applying the credit");
        EmployeePeraTaxCredit item=peraCredits.findByEmployeeIdAndTaxYear(employeeId,taxYear).orElseGet(EmployeePeraTaxCredit::new);
        item.setEmployeeId(employeeId);item.setTaxYear(taxYear);item.setQualifiedContributions(contributions);
        item.setOverseasFilipino(input.overseasFilipino());item.setTaxCreditAmount(credit);item.setCertificateNumber(certificate);
        item.setCertificateReceived(input.certificateReceived());
        audit.log("EmployeePeraTaxCredit",employeeId,"UPDATE","PERA Tax Credit Certificate data for "+taxYear+" updated by "+actor.id());
        return peraProfile(peraCredits.save(item));
    }

    private static PeraTaxCreditProfile peraProfile(EmployeePeraTaxCredit item) {
        return new PeraTaxCreditProfile(item.getEmployeeId(),item.getTaxYear(),item.getQualifiedContributions(),
                Boolean.TRUE.equals(item.getOverseasFilipino()),item.getTaxCreditAmount(),item.getCertificateNumber(),Boolean.TRUE.equals(item.getCertificateReceived()));
    }

    private static BigDecimal amount(BigDecimal value) {
        if (value == null) return BigDecimal.ZERO;
        if (value.signum() < 0 || value.scale() > 2) throw new com.apex.hrm.common.BusinessException("Prior-employer amounts must be non-negative and limited to cents");
        return value;
    }

    private static PriorEmployerProfile priorProfile(Employee employee) {
        return new PriorEmployerProfile(employee.getPriorEmployerName(), employee.getPriorEmployerTin(), employee.getPriorEmployerAddress(),
                employee.getPriorEmployerRdoCode(), employee.getPriorEmployerZipCode(),
                employee.getPriorEmployerCompensation(), employee.getPriorEmployerTaxableCompensation(),
                employee.getPriorEmployerNonTaxableCompensation(), employee.getPriorEmployerTaxWithheld(),
                employee.getPriorEmployerCertificateReceived(),employee.getPriorNontaxBasicSmw(),employee.getPriorNontaxHolidayPay(),
                employee.getPriorNontaxOvertimePay(),employee.getPriorNontaxNightDiff(),employee.getPriorNontaxHazardPay(),
                employee.getPriorNontax13thMonth(),employee.getPriorNontaxDeminimis(),employee.getPriorNontaxSssEtc(),
                employee.getPriorNontaxSalaries(),employee.getPriorTaxableBasicSalary(),employee.getPriorTaxable13thMonth(),
                employee.getPriorTaxableSalaries());
    }

    private static TaxProfile taxProfile(Employee e) {
        return new TaxProfile(e.getTin(),e.getMinimumWageEarner(),e.getEmployeeRdoCode(),e.getRegisteredZipCode(),
                e.getLocalHomeAddress(),e.getLocalHomeZipCode(),e.getForeignAddress(),e.getStatutoryMinimumWageDaily(),
                e.getStatutoryMinimumWageMonthly(),e.getMobile(),e.getBranchCode(),e.getRegionNum(),e.getNationality(),
                e.getBirEmploymentStatus(),e.getBirReasonSeparation(),e.getBirSubstitutedFiling(),e.getMinimumWageFactorUsed());
    }
    private static EmployerTaxProfile employerTaxProfile(Company c) {
        return new EmployerTaxProfile(c.getId(),c.getLegalName(),c.getTin(),c.getBranchCode(),c.getAddress(),c.getRdoCode(),c.getZipCode(),c.getEmployerType(),c.getMainEmployer());
    }
    private static String clean(String value) { return value==null||value.isBlank()?null:value.trim(); }
    private static void validateZip(String value) { if(clean(value)!=null&&!value.trim().matches("[0-9]{4}"))throw new com.apex.hrm.common.BusinessException("ZIP code must contain four digits"); }
    private static void validateRdo(String value) { if(clean(value)!=null&&!value.trim().matches("[0-9]{1,3}"))throw new com.apex.hrm.common.BusinessException("RDO code must contain one to three digits"); }
    private static void validateBranchCode(String value) { if(clean(value)!=null&&!value.trim().matches("[0-9]{4}"))throw new com.apex.hrm.common.BusinessException("BIR branch code must contain four digits"); }
    private static void validateRegionNum(String value) { if(clean(value)!=null&&!value.trim().matches("[0-9]{4}"))throw new com.apex.hrm.common.BusinessException("BIR region number must contain four digits"); }
    private static void validateTin(String value,String label) { if(value==null)return;String digits=value.replace("-","");if(!value.matches("[0-9-]+")||(digits.length()!=9&&digits.length()!=12))throw new com.apex.hrm.common.BusinessException(label+" must contain 9 or 12 digits, optionally separated by hyphens"); }
    private static void nonNegative(BigDecimal value,String name) { if(value!=null&&(value.signum()<0||value.scale()>2))throw new com.apex.hrm.common.BusinessException(name+" must be non-negative and limited to cents"); }
    private static void validateTaxYear(int year) { if(year<2000||year>LocalDate.now().getYear()+1)throw new com.apex.hrm.common.BusinessException("Choose a valid tax year"); }

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

    /** Printable, unsigned BIR Form 2316 working draft. Unknown profile fields remain blank for review. */
    @GetMapping("/reports/bir-2316-draft")
    public Bir2316Draft bir2316Draft(@org.springframework.web.bind.annotation.RequestParam int year,
                                     @org.springframework.web.bind.annotation.RequestParam UUID employeeId) {
        if (year < 2000 || year > LocalDate.now().getYear() + 1) {
            throw new com.apex.hrm.common.BusinessException("Choose a valid tax year");
        }
        LocalDate from=LocalDate.of(year,1,1),to=LocalDate.of(year,12,31);
        Employee employee=employees.findById(employeeId).orElseThrow(()->new NotFoundException("Employee",employeeId));
        AnnualCertificateRow summary=certificateRow(employee,from,to);
        List<PayrollRun> employeeRuns=runs.findFinalizedCompensationBetweenForCompany(from,to,employee.getCompanyId()).stream()
                .filter(run->payrollEmployees.findByRunId(run.getId()).stream().anyMatch(row->employeeId.equals(row.getEmployeeId())))
                .toList();
        Map<String,BigDecimal> items=bir2316CompensationItems(employee,employeeRuns);
        BigDecimal presentTaxable=taxable2316Total(items);
        BigDecimal previousTaxable=summary.priorEmployerTaxableCompensation()==null?BigDecimal.ZERO:summary.priorEmployerTaxableCompensation();
        BigDecimal grossTaxable=presentTaxable.add(previousTaxable);
        BigDecimal priorWithheld=summary.priorEmployerTaxWithheld()==null?BigDecimal.ZERO:summary.priorEmployerTaxWithheld();
        BigDecimal presentWithheld=summary.withholdingTax().subtract(priorWithheld).max(BigDecimal.ZERO);
        PeraTaxCreditProfile peraProfile=peraCredits.findByEmployeeIdAndTaxYear(employeeId,year)
                .map(PayrollController::peraProfile).orElse(new PeraTaxCreditProfile(employeeId,year,BigDecimal.ZERO,false,BigDecimal.ZERO,null,false));
        BigDecimal taxDue=annualTaxDue(to,grossTaxable);
        BigDecimal certifiedPera=peraProfile.certificateReceived()?peraProfile.taxCreditAmount():BigDecimal.ZERO;
        BigDecimal pera=certifiedPera.min(taxDue.subtract(presentWithheld.add(priorWithheld)).max(BigDecimal.ZERO));
        BigDecimal totalWithheld=presentWithheld.add(priorWithheld).add(pera);
        Company company=companies.findById(employee.getCompanyId()).orElseThrow(() -> new NotFoundException("Company",employee.getCompanyId()));
        TaxProfile personal=taxProfile(employee); EmployerTaxProfile employerProfile=employerTaxProfile(company);
        List<String> manual=new ArrayList<>();
        if(hasPriorEmployer(employee)&&!Boolean.TRUE.equals(employee.getPriorEmployerCertificateReceived())) manual.add("Verify prior-employer figures against its Form 2316");
        manual.add("Verify benefit classifications and 13th-month / other-benefit allocation against source payroll");
        if(!peraProfile.certificateReceived()) manual.add("Verify whether a PERA Tax Credit Certificate applies for this year");
        else if(certifiedPera.compareTo(pera)>0) manual.add("PERA TCC amount exceeds remaining tax liability; confirm the unused credit balance is not applied");
        manual.add("Present employer and employee signatures and dates");
        manual.add("Substituted filing declaration, when applicable");
        return new Bir2316Draft(summary,Map.copyOf(items),presentTaxable,previousTaxable,grossTaxable,
                taxDue,presentWithheld,priorWithheld,totalWithheld,pera,
                Boolean.TRUE.equals(employee.getMinimumWageEarner()),"September 2021 ENCS",true,
                List.copyOf(manual),personal,employerProfile,priorProfile(employee),peraProfile,"NOT_SUBMITTED");
    }

    @GetMapping("/reports/bir-2316-issued")
    public Bir2316Draft issuedBir2316(@org.springframework.web.bind.annotation.RequestParam int year,
                                      @org.springframework.web.bind.annotation.RequestParam UUID employeeId,
                                      @AuthenticationPrincipal HrmUserPrincipal actor) {
        return tenantSql.inTenant(actor.tenantId(),()->{
            Bir2316Draft draft=bir2316Draft(year,employeeId);
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT status,source_fingerprint FROM bir2316_review WHERE tenant_id=? AND employee_id=? AND tax_year=?",actor.tenantId(),employeeId,year);
            if(rows.isEmpty()||!"APPROVED".equals(rows.get(0).get("status"))||!reviewFingerprint(draft).equals(rows.get(0).get("source_fingerprint")))
                throw new com.apex.hrm.common.BusinessException("Form 2316 must be reviewed and approved against the current payroll and tax profile before printing");
            return withReviewStatus(draft,"APPROVED");
        });
    }

    @GetMapping("/reports/bir-2316-reviews")
    public List<ReviewStatus> bir2316Reviews(@org.springframework.web.bind.annotation.RequestParam int year,
                                             @AuthenticationPrincipal HrmUserPrincipal actor) {
        if(year<2000||year>LocalDate.now().getYear()+1) throw new com.apex.hrm.common.BusinessException("Choose a valid tax year");
        return tenantSql.inTenant(actor.tenantId(),()->jdbc.query("""
SELECT r.id,r.employee_id,e.employee_no,concat_ws(' ',e.last_name,e.first_name,e.middle_name,e.suffix) employee_name,
 r.tax_year,r.status,r.submitted_by,r.submitted_at,r.decided_by,r.decided_at,r.decision_note,r.source_fingerprint
FROM bir2316_review r JOIN employee e ON e.tenant_id=r.tenant_id AND e.id=r.employee_id
WHERE r.tenant_id=? AND r.tax_year=? ORDER BY e.last_name,e.first_name,e.employee_no
""",(rs,n)->{
            UUID employeeId=rs.getObject("employee_id",UUID.class);
            Bir2316Draft current=bir2316Draft(year,employeeId);
            return new ReviewStatus(rs.getObject("id",UUID.class),employeeId,rs.getString("employee_no"),rs.getString("employee_name"),
                    rs.getInt("tax_year"),rs.getString("status"),rs.getObject("submitted_by",UUID.class),rs.getTimestamp("submitted_at").toInstant(),
                    rs.getObject("decided_by",UUID.class),rs.getTimestamp("decided_at")==null?null:rs.getTimestamp("decided_at").toInstant(),
                    rs.getString("decision_note"),!reviewFingerprint(current).equals(rs.getString("source_fingerprint")));
        },actor.tenantId(),year));
    }

    @PostMapping("/reports/bir-2316-reviews")
    public ReviewStatus submitBir2316Review(@org.springframework.web.bind.annotation.RequestParam int year,
                                            @org.springframework.web.bind.annotation.RequestParam UUID employeeId,
                                            @AuthenticationPrincipal HrmUserPrincipal actor) {
        requirePayrollRole(actor);
        return tenantSql.inTenant(actor.tenantId(),()->{
        Bir2316Draft draft=bir2316Draft(year,employeeId);
        if(!draft.summary().readyForReview()) throw new com.apex.hrm.common.BusinessException("Complete required employee, employer, and prior-employer tax profile fields before submitting Form 2316 for review");
        String fingerprint=reviewFingerprint(draft);
        jdbc.update("""
INSERT INTO bir2316_review(tenant_id,employee_id,tax_year,status,source_fingerprint,submitted_by)
VALUES(?,?,?,'PENDING',?,?)
ON CONFLICT(tenant_id,tax_year,employee_id) DO UPDATE SET status='PENDING',source_fingerprint=EXCLUDED.source_fingerprint,
 submitted_by=EXCLUDED.submitted_by,submitted_at=now(),decided_by=NULL,decided_at=NULL,decision_note=NULL
WHERE bir2316_review.status<>'APPROVED' OR bir2316_review.source_fingerprint<>EXCLUDED.source_fingerprint
""",actor.tenantId(),employeeId,year,fingerprint,actor.id());
        audit.log("Bir2316Review",employeeId,"SUBMIT","Form 2316 year "+year+" submitted for independent review by "+actor.id());
        return bir2316Reviews(year,actor).stream().filter(row->employeeId.equals(row.employeeId())).findFirst().orElseThrow();
        });
    }

    @PostMapping("/reports/bir-2316-reviews/{reviewId}/decision")
    public ReviewStatus decideBir2316Review(@PathVariable UUID reviewId,@RequestBody ReviewDecision decision,
                                            @AuthenticationPrincipal HrmUserPrincipal actor) {
        if(!hasRole(actor,"ROLE_TENANT_ADMIN")&&!hasRole(actor,"ROLE_HR_ADMIN"))
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN,"An HR administrator or tenant administrator must approve or reject Form 2316");
        return tenantSql.inTenant(actor.tenantId(),()->{
        String outcome=decision==null||decision.decision()==null?"":decision.decision().trim().toUpperCase();
        if(!Set.of("APPROVE","REJECT").contains(outcome)) throw new com.apex.hrm.common.BusinessException("Choose approve or reject");
        Map<String,Object> row=jdbc.queryForMap("SELECT employee_id,tax_year,status,source_fingerprint,submitted_by FROM bir2316_review WHERE tenant_id=? AND id=? FOR UPDATE",actor.tenantId(),reviewId);
        UUID employeeId=(UUID)row.get("employee_id"); int year=((Number)row.get("tax_year")).intValue();
        if(actor.id().equals(row.get("submitted_by"))) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN,"The preparer cannot approve their own Form 2316");
        if(!"PENDING".equals(row.get("status"))) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,"This Form 2316 review is no longer pending");
        Bir2316Draft current=bir2316Draft(year,employeeId);
        if(!reviewFingerprint(current).equals(row.get("source_fingerprint"))) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,"Payroll or tax profile changed after submission. Submit the updated certificate for review again.");
        jdbc.update("UPDATE bir2316_review SET status=?,decided_by=?,decided_at=now(),decision_note=? WHERE tenant_id=? AND id=? AND status='PENDING'",
                outcome.equals("APPROVE")?"APPROVED":"REJECTED",actor.id(),clean(decision==null?null:decision.note()),actor.tenantId(),reviewId);
        audit.log("Bir2316Review",reviewId,outcome,"Form 2316 decision recorded by "+actor.id());
        return bir2316Reviews(year,actor).stream().filter(r->reviewId.equals(r.id())).findFirst().orElseThrow();
        });
    }

    private boolean hasRole(HrmUserPrincipal actor,String role) { return actor.getAuthorities().stream().anyMatch(a->role.equals(a.getAuthority())); }
    private void requirePayrollRole(HrmUserPrincipal actor) {
        if(!hasRole(actor,"ROLE_TENANT_ADMIN")&&!hasRole(actor,"ROLE_HR_ADMIN")&&!hasRole(actor,"ROLE_PAYROLL_OFFICER"))
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN,"Payroll officer or HR administrator role required");
    }
    private String reviewFingerprint(Bir2316Draft draft) {
        String source=draft.summary().toString()+new TreeMap<>(draft.compensationItems())+draft.taxDue()+draft.employeeTaxProfile()+draft.employerTaxProfile()+draft.priorEmployerTaxProfile()+draft.peraTaxCreditProfile();
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private Bir2316Draft withReviewStatus(Bir2316Draft d,String status) {
        return new Bir2316Draft(d.summary(),d.compensationItems(),d.taxableCompensationPresentEmployer(),d.taxableCompensationPreviousEmployer(),
                d.grossTaxableCompensation(),d.taxDue(),d.presentEmployerTaxWithheld(),d.previousEmployerTaxWithheld(),d.totalTaxesWithheld(),
                d.peraTaxCredit(),d.minimumWageEarner(),d.formVersion(),d.draft(),d.manualCompletionFields(),d.employeeTaxProfile(),
                d.employerTaxProfile(),d.priorEmployerTaxProfile(),d.peraTaxCreditProfile(),status);
    }

    private Map<String,BigDecimal> bir2316CompensationItems(Employee employee,List<PayrollRun> employeeRuns) {
        Map<String,BigDecimal> result=new LinkedHashMap<>();
        for(int i=29;i<=51;i++) result.put(String.valueOf(i),BigDecimal.ZERO);
        result.put("44A",BigDecimal.ZERO);result.put("44B",BigDecimal.ZERO);
        result.put("51A",BigDecimal.ZERO);result.put("51B",BigDecimal.ZERO);
        List<PayrollLine> employeeLines=employeeRuns.stream().flatMap(run->lines.findByRunId(run.getId()).stream())
                .filter(line->employee.getId().equals(line.getEmployeeId())).toList();
        BigDecimal basic=employeeLines.stream().filter(line->"EARNING".equals(line.getCategory())&&"BASIC".equals(line.getItemCode()))
                .map(PayrollLine::getAmount).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal nonTaxableBasic=Boolean.TRUE.equals(employee.getMinimumWageEarner())?basic:basic.min(new BigDecimal("250000"));
        result.put("29",nonTaxableBasic);
        result.put("39",basic.subtract(nonTaxableBasic).max(BigDecimal.ZERO));
        BigDecimal otherBenefits=BigDecimal.ZERO,exemptThirteenth=BigDecimal.ZERO;
        for(PayrollLine line:employeeLines) {
            String code=line.getItemCode();
            BigDecimal amount=line.getAmount()==null?BigDecimal.ZERO:line.getAmount();
            if("WTAX".equals(code)||"TAX_REFUND".equals(code)||"BASIC".equals(code)) continue;
            if(Set.of("SSS_EE","PHIC_EE","HDMF_EE","UNION_DUES").contains(code)) { add2316(result,"36",amount);continue; }
            if("THIRTEENTH_MONTH".equals(code)) { if(Boolean.TRUE.equals(line.getTaxable()))add2316(result,"48",amount);else exemptThirteenth=exemptThirteenth.add(amount);continue; }
            if("DE_MINIMIS".equals(code)) { add2316(result,"35",amount);continue; }
            if("OT".equals(code)||"OVERTIME".equals(code)) { add2316(result,Boolean.TRUE.equals(employee.getMinimumWageEarner())?"31":"50",amount);continue; }
            if("ND".equals(code)||"NIGHT_DIFF".equals(code)) { add2316(result,Boolean.TRUE.equals(employee.getMinimumWageEarner())?"32":"51B",amount);continue; }
            if("HAZARD_PAY".equals(code)) { add2316(result,Boolean.TRUE.equals(employee.getMinimumWageEarner())?"33":"49",amount);continue; }
            if("HOLIDAY_PAY".equals(code)||"PREMIUM".equals(code)) { add2316(result,Boolean.TRUE.equals(employee.getMinimumWageEarner())?"30":"51B",amount);continue; }
            if(!"EARNING".equals(line.getCategory())) continue;
            if(!Boolean.TRUE.equals(line.getTaxable())) {
                if(usesSharedBenefitCeiling(line))otherBenefits=otherBenefits.add(amount);else add2316(result,"37",amount);
                continue;
            }
            String item=switch(code) {
                case "REPRESENTATION" -> "40"; case "TRANSPORTATION" -> "41"; case "COLA" -> "42";
                case "HOUSING" -> "43"; case "COMMISSION" -> "45"; case "PROFIT_SHARING" -> "46";
                case "DIRECTOR_FEES" -> "47"; default -> "51B";
            };
            add2316(result,item,amount);
        }
        BigDecimal sharedBenefitTotal=otherBenefits.add(exemptThirteenth);
        add2316(result,"34",sharedBenefitTotal.min(com.apex.hrm.payroll.engine.ThirteenthMonthCalculator.EXEMPT_CEILING));
        add2316(result,"51B",sharedBenefitTotal.subtract(com.apex.hrm.payroll.engine.ThirteenthMonthCalculator.EXEMPT_CEILING).max(BigDecimal.ZERO));
        BigDecimal nonTaxable=BigDecimal.ZERO;
        for(int i=29;i<=37;i++) nonTaxable=nonTaxable.add(result.get(String.valueOf(i)));
        result.put("38",nonTaxable);
        return result;
    }

    private static BigDecimal taxable2316Total(Map<String,BigDecimal> items) {
        return List.of("39","40","41","42","43","44A","44B","45","46","47","48","49","50","51A","51B")
                .stream().map(items::get).filter(value -> value != null).reduce(BigDecimal.ZERO,BigDecimal::add);
    }

    private static void add2316(Map<String,BigDecimal> items,String key,BigDecimal amount) {
        items.put(key,items.getOrDefault(key,BigDecimal.ZERO).add(amount));
    }

    private static boolean usesSharedBenefitCeiling(PayrollLine line) {
        return "EARNING".equals(line.getCategory())&&Boolean.FALSE.equals(line.getTaxable())
                &&!Set.of("BASIC","HOLIDAY_PAY","PREMIUM","OT","OVERTIME","ND","NIGHT_DIFF","HAZARD_PAY",
                        "THIRTEENTH_MONTH","DE_MINIMIS","TAX_REFUND").contains(line.getItemCode());
    }

    private BigDecimal annualTaxDue(LocalDate asOf,BigDecimal taxable) {
        List<WithholdingTaxBracket> all=taxBrackets.findByPeriodTypeAndEffectiveFromLessThanEqual("ANNUAL",asOf);
        LocalDate effective=all.stream().map(WithholdingTaxBracket::getEffectiveFrom).max(LocalDate::compareTo)
                .orElseThrow(()->new com.apex.hrm.common.BusinessException("No annual withholding tax table is configured"));
        List<WithholdingTaxCalculator.Bracket> brackets=all.stream().filter(b->effective.equals(b.getEffectiveFrom()))
                .map(b->new WithholdingTaxCalculator.Bracket(b.getLowerBound(),b.getBaseTax(),b.getRate(),b.getUpperBound())).toList();
        return WithholdingTaxCalculator.tax(brackets,taxable);
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

    /** Company-level 1604-C / Alphalist reconciliation workpaper. This is not a BIR DAT import file. */
    @GetMapping("/reports/bir-1604c-reconciliation")
    public AlphalistReport alphalistReconciliation(@org.springframework.web.bind.annotation.RequestParam int year,
                                                    @org.springframework.web.bind.annotation.RequestParam UUID companyId,
                                                    @AuthenticationPrincipal HrmUserPrincipal actor) {
        validateTaxYear(year);
        return tenantSql.inTenant(actor.tenantId(), () -> buildAlphalistReport(year, companyId, actor));
    }

    @GetMapping(value = "/reports/bir-1604c-reconciliation.csv", produces = "text/csv")
    public ResponseEntity<byte[]> alphalistReconciliationCsv(@org.springframework.web.bind.annotation.RequestParam int year,
                                                              @org.springframework.web.bind.annotation.RequestParam UUID companyId,
                                                              @AuthenticationPrincipal HrmUserPrincipal actor) {
        AlphalistReport report=alphalistReconciliation(year,companyId,actor);
        StringBuilder out=new StringBuilder("Schedule,Sequence,Employee No,Employee Name,Employee TIN,Employment From,Employment To,Finalized Payroll Runs,Present Employer Gross,Present Employer Non-Taxable,Present Employer Taxable,Previous Employer Gross,Previous Employer Non-Taxable,Previous Employer Taxable,Combined Taxable Compensation,Annual Tax Due,Present Employer Tax Withheld,Previous Employer Tax Withheld,PERA Tax Credit,Tax Withheld As Adjusted,Form 2316 Review Status,Exceptions\r\n");
        for(AlphalistRow row:report.rows()) out.append(row.schedule()).append(',').append(row.sequence()).append(',')
                .append(csv(row.employeeNo())).append(',').append(csv(row.employeeName())).append(',').append(csv(row.tin())).append(',')
                .append(csv(row.employmentFrom())).append(',').append(csv(row.employmentTo())).append(',').append(row.finalizedPayrollRuns()).append(',')
                .append(row.presentEmployerGross()).append(',').append(row.presentEmployerNonTaxable()).append(',').append(row.presentEmployerTaxable()).append(',')
                .append(row.previousEmployerGross()).append(',').append(row.previousEmployerNonTaxable()).append(',').append(row.previousEmployerTaxable()).append(',')
                .append(row.combinedTaxable()).append(',').append(row.annualTaxDue()).append(',').append(row.presentEmployerTaxWithheld()).append(',')
                .append(row.previousEmployerTaxWithheld()).append(',').append(row.peraTaxCredit()).append(',').append(row.adjustedTaxWithheld()).append(',')
                .append(csv(row.form2316Status())).append(',').append(csv(String.join("; ",row.exceptions()))).append("\r\n");
        String notice="# Internal reconciliation workpaper; not an official BIR DAT import or filed return.\r\n"
                +"# Employer,"+csv(report.employerName())+",TIN,"+csv(report.employerTin())+",Tax year,"+year+"\r\n"
                +"# Employees,"+report.employeeCount()+",Schedule 1,"+report.schedule1Count()+",Schedule 2,"+report.schedule2Count()+",Exceptions,"+report.exceptionCount()+"\r\n";
        byte[] body=(notice+out).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=bir-1604c-alphalist-reconciliation-"+year+".csv")
                .header("X-Report-Employee-Count",String.valueOf(report.employeeCount()))
                .header("X-Report-Schedule1-Count",String.valueOf(report.schedule1Count()))
                .header("X-Report-Schedule2-Count",String.valueOf(report.schedule2Count()))
                .header("X-Report-Exception-Count",String.valueOf(report.exceptionCount()))
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8")).body(body);
    }

    @GetMapping(value = "/reports/bir-1604c.dat", produces = "application/octet-stream")
    public ResponseEntity<byte[]> alphalistDat(@org.springframework.web.bind.annotation.RequestParam int year,
                                                @org.springframework.web.bind.annotation.RequestParam UUID companyId,
                                                @AuthenticationPrincipal HrmUserPrincipal actor) {
        validateTaxYear(year);
        return tenantSql.inTenant(actor.tenantId(), () -> {
            AlphalistReport report=buildAlphalistReport(year,companyId,actor);
            if(report.rows().isEmpty()) throw new com.apex.hrm.common.BusinessException("No employees with finalized payroll or prior-employer income are eligible for the selected tax year");
            if(report.exceptionCount()>0) throw new com.apex.hrm.common.BusinessException("BIR DAT export blocked: resolve all " + report.exceptionCount() + " employee record(s) in the Alphalist reconciliation before exporting");
            Company company=companies.findById(companyId).orElseThrow(()->new NotFoundException("Company",companyId));
            String tin=company.getTin().replace("-","");
            String period="12/31/"+year;
            List<List<String>> schedule1=new ArrayList<>(),schedule2=new ArrayList<>();
            LocalDate from=LocalDate.of(year,1,1),to=LocalDate.of(year,12,31);
            Map<String,Employee> employeeByNo=new LinkedHashMap<>();
            employees.findAll(Sort.by(Sort.Direction.ASC,"lastName","firstName","employeeNo")).stream()
                    .filter(e->companyId.equals(e.getCompanyId())).forEach(e->employeeByNo.put(e.getEmployeeNo(),e));
            int seq1=0,seq2=0;
            for(AlphalistRow row:report.rows()) {
                Employee e=employeeByNo.get(row.employeeNo());
                if(e==null) throw new com.apex.hrm.common.BusinessException("Employee data changed while generating the Alphalist; reload the report and retry");
                Bir2316Draft d=bir2316Draft(year,e.getId());
                if("SCHEDULE_2_MWE".equals(row.schedule())) schedule2.add(schedule2Detail(e,d,company,year,++seq2,period));
                else schedule1.add(schedule1Detail(e,d,company,year,++seq1,period));
            }
            StringBuilder dat=new StringBuilder();
            appendDat(dat,List.of("H1604C",tin,company.getBranchCode(),period));
            schedule1.forEach(row->appendDat(dat,row));
            appendDat(dat,schedule1Control(schedule1,tin,company.getBranchCode(),period));
            schedule2.forEach(row->appendDat(dat,row));
            appendDat(dat,schedule2Control(schedule2,tin,company.getBranchCode(),period));
            String filename=tin+company.getBranchCode()+"1231"+year+"1604C.DAT";
            audit.log("Bir1604CAlphalist",companyId,"EXPORT","BIR Form 1604-C Alphalist DAT generated for tax year "+year+" by "+actor.id());
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename="+filename)
                    .header("X-Report-Employee-Count",String.valueOf(report.employeeCount()))
                    .contentType(MediaType.parseMediaType("text/plain; charset=windows-1252"))
                    .body(dat.toString().getBytes(java.nio.charset.Charset.forName("windows-1252")));
        });
    }

    private List<String> schedule1Detail(Employee e,Bir2316Draft d,Company c,int year,int seq,String period) {
        Map<String,BigDecimal> x=d.compensationItems();
        BigDecimal pn=amount(e.getPriorEmployerNonTaxableCompensation()),pt=amount(e.getPriorEmployerTaxableCompensation());
        BigDecimal priorBasic=amount(e.getPriorNontaxBasicSmw()),prior13=amount(e.getPriorNontax13thMonth()),priorDm=amount(e.getPriorNontaxDeminimis()),priorSss=amount(e.getPriorNontaxSssEtc());
        BigDecimal priorOther=pn.subtract(priorBasic).subtract(prior13).subtract(priorDm).subtract(priorSss);
        BigDecimal presNonTax=comp(x,"38"),presBasic=comp(x,"29"),pres13=comp(x,"34"),presDm=comp(x,"35"),presSss=comp(x,"36"),presOther=comp(x,"37");
        BigDecimal taxBasic=comp(x,"39"),tax13=comp(x,"48"),taxOther=taxableOther(x),presTax=taxBasic.add(tax13).add(taxOther);
        List<String> r=datRow(49);
        set(r,1,"D1");set(r,2,"1604C");set(r,3,digits(c.getTin()));set(r,4,c.getBranchCode());set(r,5,period);set(r,6,seq);
        set(r,7,digits(e.getTin()));set(r,8,e.getBranchCode());set(r,9,e.getLastName());set(r,10,e.getFirstName());set(r,11,e.getMiddleName());set(r,12,e.getRegionNum());
        set(r,13,pn);set(r,14,priorBasic);set(r,15,prior13);set(r,16,priorDm);set(r,17,priorSss);set(r,18,priorOther);set(r,19,pn);
        set(r,20,amount(e.getPriorTaxableBasicSalary()));set(r,21,amount(e.getPriorTaxable13thMonth()));set(r,22,amount(e.getPriorTaxableSalaries()));set(r,23,pt);
        set(r,24,employmentDate(e.getHireDate(),year,false));set(r,25,employmentDate(e.getSeparationDate(),year,true));
        set(r,26,presNonTax);set(r,27,presBasic);set(r,28,pres13);set(r,29,presDm);set(r,30,presSss);set(r,31,presOther);set(r,32,presNonTax);
        set(r,33,taxBasic);set(r,34,tax13);set(r,35,taxOther);set(r,36,presTax);set(r,37,pt.add(presTax));set(r,38,d.grossTaxableCompensation());set(r,39,d.taxDue());
        set(r,40,d.previousEmployerTaxWithheld());set(r,41,d.presentEmployerTaxWithheld());set(r,42,decemberWithheld(e.getId(),year,c.getId()));set(r,43,overWithheld(e.getId(),year,c.getId()));
        set(r,44,d.presentEmployerTaxWithheld().add(d.previousEmployerTaxWithheld()));set(r,45,e.getNationality());set(r,46,e.getBirEmploymentStatus());
        set(r,47,e.getSeparationDate()!=null&&e.getSeparationDate().getYear()==year?e.getBirReasonSeparation():"NA");set(r,48,e.getBirSubstitutedFiling());set(r,49,d.peraTaxCredit());
        return r;
    }

    private List<String> schedule2Detail(Employee e,Bir2316Draft d,Company c,int year,int seq,String period) {
        Map<String,BigDecimal> x=d.compensationItems();
        BigDecimal pn=amount(e.getPriorEmployerNonTaxableCompensation()),pt=amount(e.getPriorEmployerTaxableCompensation());
        BigDecimal priorOther=pn.subtract(amount(e.getPriorNontaxBasicSmw())).subtract(amount(e.getPriorNontaxHolidayPay())).subtract(amount(e.getPriorNontaxOvertimePay()))
                .subtract(amount(e.getPriorNontaxNightDiff())).subtract(amount(e.getPriorNontaxHazardPay())).subtract(amount(e.getPriorNontax13thMonth()))
                .subtract(amount(e.getPriorNontaxDeminimis())).subtract(amount(e.getPriorNontaxSssEtc()));
        BigDecimal nonTax=comp(x,"38"),taxable=d.taxableCompensationPresentEmployer(),gross=nonTax.add(taxable);
        BigDecimal factor=BigDecimal.valueOf(e.getMinimumWageFactorUsed());
        BigDecimal daily=amount(e.getStatutoryMinimumWageDaily()),monthly=amount(e.getStatutoryMinimumWageMonthly());
        if(daily.signum()==0&&monthly.signum()>0)daily=monthly.multiply(BigDecimal.valueOf(12)).divide(factor,2,java.math.RoundingMode.HALF_UP);
        if(monthly.signum()==0&&daily.signum()>0)monthly=daily.multiply(factor).divide(BigDecimal.valueOf(12),2,java.math.RoundingMode.HALF_UP);
        List<String> r=datRow(59);set(r,1,"D2");set(r,2,"1604C");set(r,3,digits(c.getTin()));set(r,4,c.getBranchCode());set(r,5,period);set(r,6,seq);
        set(r,7,digits(e.getTin()));set(r,8,e.getBranchCode());set(r,9,e.getLastName());set(r,10,e.getFirstName());set(r,11,e.getMiddleName());set(r,12,e.getRegionNum());
        set(r,13,pn);set(r,14,e.getPriorNontaxBasicSmw());set(r,15,e.getPriorNontaxHolidayPay());set(r,16,e.getPriorNontaxOvertimePay());set(r,17,e.getPriorNontaxNightDiff());
        set(r,18,e.getPriorNontaxHazardPay());set(r,19,e.getPriorNontax13thMonth());set(r,20,e.getPriorNontaxDeminimis());set(r,21,e.getPriorNontaxSssEtc());set(r,22,priorOther);set(r,23,pn);
        set(r,24,e.getPriorTaxable13thMonth());set(r,25,e.getPriorTaxableSalaries());set(r,26,pt);set(r,27,employmentDate(e.getHireDate(),year,false));set(r,28,employmentDate(e.getSeparationDate(),year,true));
        set(r,29,nonTax);set(r,30,daily);set(r,31,monthly);set(r,32,daily.multiply(factor));
        set(r,33,e.getMinimumWageFactorUsed());set(r,34,comp(x,"30"));set(r,35,comp(x,"31"));set(r,36,comp(x,"32"));set(r,37,comp(x,"33"));
        set(r,38,comp(x,"34"));set(r,39,comp(x,"35"));set(r,40,comp(x,"36"));set(r,41,comp(x,"37"));set(r,42,nonTax);
        set(r,43,comp(x,"48"));set(r,44,taxable.subtract(comp(x,"48")));set(r,45,gross);set(r,46,amount(e.getPriorEmployerCompensation()).add(gross));
        set(r,47,d.grossTaxableCompensation());set(r,48,d.taxDue());set(r,49,d.previousEmployerTaxWithheld());set(r,50,d.presentEmployerTaxWithheld());
        set(r,51,decemberWithheld(e.getId(),year,c.getId()));set(r,52,overWithheld(e.getId(),year,c.getId()));set(r,53,d.presentEmployerTaxWithheld().add(d.previousEmployerTaxWithheld()));
        set(r,54,e.getNationality());set(r,55,e.getBirEmploymentStatus());set(r,56,e.getSeparationDate()!=null&&e.getSeparationDate().getYear()==year?e.getBirReasonSeparation():"NA");
        set(r,57,e.getBirSubstitutedFiling());set(r,58,d.peraTaxCredit());set(r,59,comp(x,"29").subtract(comp(x,"36")).max(BigDecimal.ZERO));return r;
    }

    private List<String> schedule1Control(List<List<String>> rows,String tin,String branch,String period) {
        int[] detailColumns={13,14,15,16,17,18,19,20,21,22,23,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,49};
        List<String> out=datRow(36);set(out,1,"C1");set(out,2,"1604C");set(out,3,digits(tin));set(out,4,branch);set(out,5,period);
        for(int i=0;i<detailColumns.length;i++)set(out,6+i,sumColumn(rows,detailColumns[i]));return out;
    }
    private List<String> schedule2Control(List<List<String>> rows,String tin,String branch,String period) {
        int[] detailColumns={13,14,15,16,17,18,19,20,21,22,23,24,25,26,29,30,31,32,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,58,59};
        List<String> out=datRow(45);set(out,1,"C2");set(out,2,"1604C");set(out,3,digits(tin));set(out,4,branch);set(out,5,period);
        for(int i=0;i<detailColumns.length;i++)set(out,6+i,sumColumn(rows,detailColumns[i]));return out;
    }
    private BigDecimal decemberWithheld(UUID employeeId,int year,UUID companyId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(l.amount),0) FROM payroll_line l JOIN payroll_run r ON r.id=l.run_id JOIN pay_group g ON g.id=r.pay_group_id JOIN pay_period p ON p.id=r.pay_period_id WHERE l.employee_id=? AND r.tenant_id=? AND g.company_id=? AND r.status IN ('LOCKED','PAID') AND p.end_date BETWEEN ? AND ? AND l.item_code='WTAX'",BigDecimal.class,employeeId,com.apex.hrm.common.TenantContext.get(),companyId,LocalDate.of(year,12,1),LocalDate.of(year,12,31));
    }
    private BigDecimal overWithheld(UUID employeeId,int year,UUID companyId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(l.amount),0) FROM payroll_line l JOIN payroll_run r ON r.id=l.run_id JOIN pay_group g ON g.id=r.pay_group_id JOIN pay_period p ON p.id=r.pay_period_id WHERE l.employee_id=? AND r.tenant_id=? AND g.company_id=? AND r.status IN ('LOCKED','PAID') AND p.end_date BETWEEN ? AND ? AND l.item_code='TAX_REFUND'",BigDecimal.class,employeeId,com.apex.hrm.common.TenantContext.get(),companyId,LocalDate.of(year,1,1),LocalDate.of(year,12,31));
    }
    private static BigDecimal sumColumn(List<List<String>> rows,int column) { return rows.stream().map(r->new BigDecimal(r.get(column-1))).reduce(BigDecimal.ZERO,BigDecimal::add); }
    private static List<String> datRow(int count) { return new ArrayList<>(java.util.Collections.nCopies(count,"0")); }
    private static void set(List<String> row,int column,Object value) { row.set(column-1,value==null?"":value instanceof BigDecimal b?b.setScale(2,java.math.RoundingMode.HALF_UP).toPlainString():String.valueOf(value)); }
    private static BigDecimal comp(Map<String,BigDecimal> map,String key) { return map.getOrDefault(key,BigDecimal.ZERO); }
    private static BigDecimal taxableOther(Map<String,BigDecimal> map) { return map.entrySet().stream().filter(e->Set.of("40","41","42","43","44A","44B","45","46","47","49","50","51A","51B").contains(e.getKey())).map(Map.Entry::getValue).reduce(BigDecimal.ZERO,BigDecimal::add); }
    private static String digits(String value) { return value==null?"":value.replaceAll("[^0-9]",""); }
    private static void addBirTextIssue(List<String> issues,String label,String value,int maxLength) {
        if(value==null)return;
        if(value.length()>maxLength)issues.add(label+" exceeds the BIR field width of "+maxLength+" characters");
        if(value.chars().anyMatch(Character::isISOControl)||!java.nio.charset.Charset.forName("windows-1252").newEncoder().canEncode(value))
            issues.add(label+" contains characters that cannot be represented safely in the BIR DAT encoding");
    }
    private static String employmentDate(LocalDate date,int year,boolean end) { return date==null||date.getYear()!=year?(end?"12/31/"+year:"01/01/"+year):String.format("%02d/%02d/%04d",date.getMonthValue(),date.getDayOfMonth(),year); }
    private static void appendDat(StringBuilder out,List<String> fields) { out.append(fields.stream().map(PayrollController::datCsv).collect(java.util.stream.Collectors.joining(","))).append("\r\n"); }
    private static String datCsv(String value) { String v=value==null?"":value;return v.contains(",")||v.contains("\"")||v.contains("\n")?"\""+v.replace("\"","\"\"")+"\"":v; }

    private AlphalistReport buildAlphalistReport(int year,UUID companyId,HrmUserPrincipal actor) {
        Company company=companies.findById(companyId).orElseThrow(()->new NotFoundException("Company",companyId));
        LocalDate from=LocalDate.of(year,1,1),to=LocalDate.of(year,12,31);
        List<Employee> eligible=employees.findAll(Sort.by(Sort.Direction.ASC,"lastName","firstName","employeeNo")).stream()
                .filter(e->companyId.equals(e.getCompanyId()))
                .filter(e->(e.getHireDate()==null||!e.getHireDate().isAfter(to))&&(e.getSeparationDate()==null||!e.getSeparationDate().isBefore(from)))
                .toList();
        List<AlphalistRow> rows=new ArrayList<>();
        for(Employee employee:eligible) {
            AnnualCertificateRow summary=certificateRow(employee,from,to);
            if(summary.taxableRuns()==0 && !hasPriorEmployer(employee)) continue;
            Bir2316Draft draft=bir2316Draft(year,employee.getId());
            String status="NOT_SUBMITTED";
            List<Map<String,Object>> review=jdbc.queryForList("SELECT status,source_fingerprint FROM bir2316_review WHERE tenant_id=? AND employee_id=? AND tax_year=?",actor.tenantId(),employee.getId(),year);
            if(!review.isEmpty()) {
                status=String.valueOf(review.get(0).get("status"));
                if("APPROVED".equals(status)&&!reviewFingerprint(draft).equals(review.get(0).get("source_fingerprint"))) status="STALE";
            }
            List<String> exceptions=new ArrayList<>(summary.missingFields());
            if(clean(company.getBranchCode())==null) exceptions.add("Employer BIR branch code is missing");
            if(clean(company.getTin())==null||company.getTin().replace("-","").length()!=9)
                exceptions.add("Employer TIN must contain the nine-digit format required by the Alphalist file layout");
            if(!"APPROVED".equals(status)) exceptions.add("Form 2316 is "+status.toLowerCase(Locale.ROOT).replace('_',' '));
            if(clean(employee.getBranchCode())==null) exceptions.add("Employee BIR branch code is missing");
            if(employee.getTin()==null||digits(employee.getTin()).length()!=9) exceptions.add("Employee TIN must contain the nine-digit format required by the Alphalist file layout");
            addBirTextIssue(exceptions,"Employee last name",employee.getLastName(),30);
            addBirTextIssue(exceptions,"Employee first name",employee.getFirstName(),30);
            addBirTextIssue(exceptions,"Employee middle name",employee.getMiddleName(),30);
            if(clean(employee.getRegionNum())==null) exceptions.add("Assigned BIR region number is missing");
            if(clean(employee.getNationality())==null) exceptions.add("Employee nationality is missing");
            addBirTextIssue(exceptions,"Employee nationality",employee.getNationality(),30);
            if(clean(employee.getBirEmploymentStatus())==null) exceptions.add("BIR employment status is missing");
            if(clean(employee.getBirSubstitutedFiling())==null) exceptions.add("Substituted filing status is missing");
            if(Boolean.TRUE.equals(employee.getMinimumWageEarner())) {
                if(employee.getMinimumWageFactorUsed()==null) exceptions.add("MWE annual wage factor is missing");
                if(employee.getStatutoryMinimumWageDaily()==null&&employee.getStatutoryMinimumWageMonthly()==null) exceptions.add("MWE statutory daily or monthly wage is missing");
            }
            BigDecimal priorNonTax=amount(employee.getPriorEmployerNonTaxableCompensation());
            BigDecimal priorComponents=amount(employee.getPriorNontaxBasicSmw())
                    .add(amount(employee.getPriorNontax13thMonth())).add(amount(employee.getPriorNontaxDeminimis()))
                    .add(amount(employee.getPriorNontaxSssEtc()));
            if(Boolean.TRUE.equals(employee.getMinimumWageEarner())) {
                priorComponents=amount(employee.getPriorNontaxBasicSmw()).add(amount(employee.getPriorNontaxHolidayPay()))
                        .add(amount(employee.getPriorNontaxOvertimePay())).add(amount(employee.getPriorNontaxNightDiff()))
                        .add(amount(employee.getPriorNontaxHazardPay())).add(amount(employee.getPriorNontax13thMonth()))
                        .add(amount(employee.getPriorNontaxDeminimis())).add(amount(employee.getPriorNontaxSssEtc()));
            }
            if(priorComponents.compareTo(priorNonTax)!=0)
                exceptions.add("Previous-employer non-taxable total does not reconcile to its detailed categories");
            if(employee.getSeparationDate()!=null&&employee.getSeparationDate().getYear()==year&&clean(employee.getBirReasonSeparation())==null)
                exceptions.add("Reason for separation is missing");
            BigDecimal presentGross=summary.grossCompensation().subtract(summary.priorEmployerCompensation());
            BigDecimal form2316Gross=draft.compensationItems().getOrDefault("38",BigDecimal.ZERO).add(draft.taxableCompensationPresentEmployer());
            if(presentGross.subtract(form2316Gross).abs().compareTo(new BigDecimal("0.01"))>0)
                exceptions.add("Finalized payroll gross differs from the Form 2316 compensation total by "+presentGross.subtract(form2316Gross).toPlainString());
            String employmentFrom=employee.getHireDate()==null?"01/01":String.format("%02d/%02d",employee.getHireDate().getMonthValue(),employee.getHireDate().getDayOfMonth());
            String employmentTo=employee.getSeparationDate()!=null&&employee.getSeparationDate().getYear()==year
                    ?String.format("%02d/%02d",employee.getSeparationDate().getMonthValue(),employee.getSeparationDate().getDayOfMonth()):"12/31";
            rows.add(new AlphalistRow(rows.size()+1,Boolean.TRUE.equals(employee.getMinimumWageEarner())?"SCHEDULE_2_MWE":"SCHEDULE_1_EMPLOYEE",
                    employee.getEmployeeNo(),summary.employeeName(),employee.getTin(),employmentFrom,employmentTo,presentGross,
                    draft.compensationItems().getOrDefault("38",BigDecimal.ZERO),draft.taxableCompensationPresentEmployer(),
                    summary.priorEmployerCompensation(),summary.priorEmployerNonTaxableCompensation(),draft.taxableCompensationPreviousEmployer(),
                    draft.grossTaxableCompensation(),draft.taxDue(),draft.presentEmployerTaxWithheld(),draft.previousEmployerTaxWithheld(),
                    draft.peraTaxCredit(),draft.totalTaxesWithheld(),status,summary.taxableRuns(),List.copyOf(exceptions)));
        }
        BigDecimal gross=sumRows(rows,AlphalistRow::presentEmployerGross),nonTaxable=sumRows(rows,AlphalistRow::presentEmployerNonTaxable),
                taxable=sumRows(rows,AlphalistRow::presentEmployerTaxable),priorTaxable=sumRows(rows,AlphalistRow::previousEmployerTaxable),
                adjusted=sumRows(rows,AlphalistRow::adjustedTaxWithheld);
        long approved=rows.stream().filter(r->"APPROVED".equals(r.form2316Status())).count();
        long exceptions=rows.stream().filter(r->!r.exceptions().isEmpty()).count();
        return new AlphalistReport(year,companyId,company.getLegalName(),company.getTin(),rows.size(),
                (int)rows.stream().filter(r->"SCHEDULE_1_EMPLOYEE".equals(r.schedule())).count(),
                (int)rows.stream().filter(r->"SCHEDULE_2_MWE".equals(r.schedule())).count(),gross,nonTaxable,taxable,priorTaxable,adjusted,
                approved,exceptions,List.copyOf(rows),"INTERNAL_RECONCILIATION_CSV",
                "Workpaper only. Validate against the current BIR Alphalist Data Entry and Validation Module and official file structure before filing.");
    }

    private static BigDecimal sumRows(List<AlphalistRow> rows,java.util.function.Function<AlphalistRow,BigDecimal> value) {
        return rows.stream().map(value).reduce(BigDecimal.ZERO,BigDecimal::add);
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
        if (blank(employee.getEmployeeRdoCode())) missing.add("Employee RDO code");
        if (blank(employee.getRegisteredZipCode())) missing.add("Employee registered-address ZIP code");
        if (blank(employee.getLocalHomeAddress())) missing.add("Employee local home address");
        if (blank(employee.getLocalHomeZipCode())) missing.add("Employee local home-address ZIP code");
        if (blank(employee.getMobile())) missing.add("Employee contact number");
        if (Boolean.TRUE.equals(employee.getMinimumWageEarner()) && employee.getStatutoryMinimumWageDaily()==null
                && employee.getStatutoryMinimumWageMonthly()==null) missing.add("MWE statutory daily or monthly wage rate");
        if (employee.getBirthDate() == null) missing.add("Birth date");
        if (employer == null || blank(employer.getLegalName())) missing.add("Employer legal name");
        if (employer == null || blank(employer.getTin())) missing.add("Employer TIN");
        if (employer == null || blank(employer.getAddress())) missing.add("Employer address");
        if (employer == null || blank(employer.getRdoCode())) missing.add("Employer RDO");
        if (employer == null || blank(employer.getZipCode())) missing.add("Employer ZIP code");
        if (employer == null || blank(employer.getEmployerType())) missing.add("Employer type");
        if (employeeRuns.stream().noneMatch(run -> "REGULAR".equals(run.getRunType())) && !hasPriorEmployer) missing.add("No finalized regular payroll runs");
        if (hasPriorEmployer && !Boolean.TRUE.equals(employee.getPriorEmployerCertificateReceived())) missing.add("Prior employer Form 2316 confirmation");
        if (hasPriorEmployer && blank(employee.getPriorEmployerTin())) missing.add("Prior employer TIN");
        if (hasPriorEmployer && blank(employee.getPriorEmployerAddress())) missing.add("Prior employer address");
        if (hasPriorEmployer && blank(employee.getPriorEmployerZipCode())) missing.add("Prior employer ZIP code");
        if (hasPriorEmployer && blank(employee.getPriorEmployerRdoCode())) missing.add("Prior employer RDO code");
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
    private static boolean hasPriorEmployer(Employee e) {
        return !blank(e.getPriorEmployerName())||positive(e.getPriorEmployerCompensation())
                ||positive(e.getPriorEmployerTaxableCompensation())||positive(e.getPriorEmployerNonTaxableCompensation())
                ||positive(e.getPriorEmployerTaxWithheld());
    }
    private static boolean positive(BigDecimal value) { return value!=null&&value.signum()>0; }

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

    @PostMapping("/runs/{id}/publish-for-employee-review")
    public PayrollRun publishForEmployeeReview(@PathVariable UUID id) {
        return payroll.publishForEmployeeReview(id);
    }

    @GetMapping("/runs/{id}/employee-acknowledgements")
    public Map<String, Integer> employeeAcknowledgements(@PathVariable UUID id) {
        return payroll.employeeAcknowledgementCounts(id);
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
    public Adjustment createAdjustment(@RequestBody AdjustmentRequest r, @AuthenticationPrincipal HrmUserPrincipal user) {
        return adjustments.create(user.tenantId(), r.employeeId(), r.itemCode(), r.description(), r.category(), r.amount(),
                r.taxable() == null || r.taxable(), r.reason(), r.sourcePeriodId(), r.targetPeriodId(),
                r.incomeClass(), user.id());
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
