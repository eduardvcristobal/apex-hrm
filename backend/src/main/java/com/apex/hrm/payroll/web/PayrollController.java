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
                             String mobile) {}
    public record EmployerTaxProfile(UUID companyId, String legalName, String tin, String address, String rdoCode,
                                    String zipCode, String employerType, Boolean mainEmployer) {}
    public record PeraTaxCreditProfile(UUID employeeId, int taxYear, BigDecimal qualifiedContributions,
                                       boolean overseasFilipino, BigDecimal taxCreditAmount,
                                       String certificateNumber, boolean certificateReceived) {}
    public record PriorEmployerProfile(String priorEmployerName, String priorEmployerTin, String priorEmployerAddress,
                                      String priorEmployerRdoCode, String priorEmployerZipCode,
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
        validateZip(input.zipCode()); validateRdo(input.rdoCode());
        validateTin(clean(input.tin()),"Employer TIN");
        if (input.mainEmployer()==null) throw new com.apex.hrm.common.BusinessException("mainEmployer must be explicitly set");
        if (clean(input.employerType())!=null && !Set.of("PRIVATE","GOVERNMENT","NONPROFIT","OTHER").contains(input.employerType().trim().toUpperCase()))
            throw new com.apex.hrm.common.BusinessException("Employer type must be private, government, nonprofit, or other");
        company.setTin(clean(input.tin())); company.setAddress(clean(input.address())); company.setRdoCode(clean(input.rdoCode()));
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
                employee.getPriorEmployerCertificateReceived());
    }

    private static TaxProfile taxProfile(Employee e) {
        return new TaxProfile(e.getTin(),e.getMinimumWageEarner(),e.getEmployeeRdoCode(),e.getRegisteredZipCode(),
                e.getLocalHomeAddress(),e.getLocalHomeZipCode(),e.getForeignAddress(),e.getStatutoryMinimumWageDaily(),
                e.getStatutoryMinimumWageMonthly(),e.getMobile());
    }
    private static EmployerTaxProfile employerTaxProfile(Company c) {
        return new EmployerTaxProfile(c.getId(),c.getLegalName(),c.getTin(),c.getAddress(),c.getRdoCode(),c.getZipCode(),c.getEmployerType(),c.getMainEmployer());
    }
    private static String clean(String value) { return value==null||value.isBlank()?null:value.trim(); }
    private static void validateZip(String value) { if(clean(value)!=null&&!value.trim().matches("[0-9]{4}"))throw new com.apex.hrm.common.BusinessException("ZIP code must contain four digits"); }
    private static void validateRdo(String value) { if(clean(value)!=null&&!value.trim().matches("[0-9]{1,3}"))throw new com.apex.hrm.common.BusinessException("RDO code must contain one to three digits"); }
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
