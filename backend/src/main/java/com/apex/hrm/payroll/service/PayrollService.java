package com.apex.hrm.payroll.service;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.org.domain.WorkLocation;
import com.apex.hrm.org.repo.WorkLocationRepository;
import com.apex.hrm.payroll.domain.Adjustment;
import com.apex.hrm.payroll.domain.PayGroup;
import com.apex.hrm.payroll.domain.PayPeriod;
import com.apex.hrm.payroll.domain.PayrollEmployee;
import com.apex.hrm.payroll.domain.PayrollLine;
import com.apex.hrm.payroll.domain.PayrollRun;
import com.apex.hrm.payroll.domain.PremiumRule;
import com.apex.hrm.payroll.domain.StatutoryContributionRule;
import com.apex.hrm.payroll.domain.WageOrder;
import com.apex.hrm.payroll.domain.WithholdingTaxBracket;
import com.apex.hrm.payroll.engine.AttendancePayCalculator;
import com.apex.hrm.payroll.engine.AnnualTaxCalculator;
import com.apex.hrm.payroll.engine.Money;
import com.apex.hrm.payroll.engine.SalaryProration;
import com.apex.hrm.payroll.engine.ThirteenthMonthCalculator;
import com.apex.hrm.payroll.engine.PagIbigCalculator;
import com.apex.hrm.payroll.engine.PayBasis;
import com.apex.hrm.payroll.engine.PayLine;
import com.apex.hrm.payroll.engine.PhilHealthCalculator;
import com.apex.hrm.payroll.engine.SssCalculator;
import com.apex.hrm.payroll.engine.WithholdingTaxCalculator;
import com.apex.hrm.payroll.repo.AdjustmentRepository;
import com.apex.hrm.payroll.repo.EmployeePeraTaxCreditRepository;
import com.apex.hrm.payroll.repo.PayGroupRepository;
import com.apex.hrm.payroll.repo.PayPeriodRepository;
import com.apex.hrm.payroll.repo.PayrollEmployeeRepository;
import com.apex.hrm.payroll.repo.PayrollLineRepository;
import com.apex.hrm.payroll.repo.PayrollRunRepository;
import com.apex.hrm.payroll.repo.PayrollBankSettlementRepository;
import com.apex.hrm.payroll.repo.PremiumRuleRepository;
import com.apex.hrm.payroll.repo.StatutoryContributionRuleRepository;
import com.apex.hrm.payroll.repo.WageOrderRepository;
import com.apex.hrm.payroll.repo.WithholdingTaxBracketRepository;
import com.apex.hrm.personnel.domain.Compensation;
import com.apex.hrm.personnel.domain.Employee;
import com.apex.hrm.personnel.domain.EmploymentRecord;
import com.apex.hrm.personnel.domain.Loan;
import com.apex.hrm.personnel.domain.RecurringItem;
import com.apex.hrm.personnel.repo.CompensationRepository;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import com.apex.hrm.personnel.repo.EmploymentRecordRepository;
import com.apex.hrm.personnel.repo.LoanRepository;
import com.apex.hrm.personnel.repo.RecurringItemRepository;
import com.apex.hrm.platform.service.AuditService;
import com.apex.hrm.timekeeping.domain.DtrDay;
import com.apex.hrm.timekeeping.engine.DayType;
import com.apex.hrm.timekeeping.repo.DtrDayRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payroll run lifecycle and computation: DRAFT -> COMPUTED -> REVIEWED -> APPROVED -> LOCKED -> PAID.
 * Computation is repeatable until approval; locking snapshots payslips and freezes the period.
 */
@Service
public class PayrollService {
    private static final Set<String> ATTENDANCE_DEDUCTIONS = Set.of("ABSENCE", "LATE_UT");
    private static final Set<String> MWE_EXEMPT_ITEMS = Set.of("BASIC", "HOLIDAY_PAY", "PREMIUM", "OT", "ND");
    private static final Set<String> RUN_TYPES = Set.of("REGULAR", "OFF_CYCLE", "THIRTEENTH_MONTH", "YEAR_END_ADJUSTMENT");
    private static final Set<String> SETTLEMENT_TYPES = Set.of("FINAL_PAY");

    private final PayrollRunRepository runs;
    private final PayPeriodRepository periods;
    private final PayGroupRepository payGroups;
    private final PayrollEmployeeRepository payrollEmployees;
    private final PayrollLineRepository lines;
    private final AdjustmentRepository adjustments;
    private final EmploymentRecordRepository employment;
    private final EmployeeRepository employees;
    private final CompensationRepository compensations;
    private final DtrDayRepository dtrDays;
    private final RecurringItemRepository recurring;
    private final LoanRepository loans;
    private final PremiumRuleRepository premiumRules;
    private final StatutoryContributionRuleRepository statutory;
    private final WithholdingTaxBracketRepository taxBrackets;
    private final WageOrderRepository wageOrders;
    private final WorkLocationRepository locations;
    private final PayslipService payslips;
    private final AuditService audit;
    private final SettlementService settlement;
    private final PayrollBankSettlementRepository bankSettlements;
    private final EmployeePeraTaxCreditRepository peraTaxCredits;

    public PayrollService(PayrollRunRepository runs, PayPeriodRepository periods, PayGroupRepository payGroups,
                          PayrollEmployeeRepository payrollEmployees, PayrollLineRepository lines,
                          AdjustmentRepository adjustments, EmploymentRecordRepository employment,
                          EmployeeRepository employees, CompensationRepository compensations, DtrDayRepository dtrDays,
                          RecurringItemRepository recurring, LoanRepository loans, PremiumRuleRepository premiumRules,
                          StatutoryContributionRuleRepository statutory, WithholdingTaxBracketRepository taxBrackets,
                          WageOrderRepository wageOrders, WorkLocationRepository locations, PayslipService payslips,
                          AuditService audit, SettlementService settlement,
                          PayrollBankSettlementRepository bankSettlements, EmployeePeraTaxCreditRepository peraTaxCredits) {
        this.runs = runs;
        this.periods = periods;
        this.payGroups = payGroups;
        this.payrollEmployees = payrollEmployees;
        this.lines = lines;
        this.adjustments = adjustments;
        this.employment = employment;
        this.employees = employees;
        this.compensations = compensations;
        this.dtrDays = dtrDays;
        this.recurring = recurring;
        this.loans = loans;
        this.premiumRules = premiumRules;
        this.statutory = statutory;
        this.taxBrackets = taxBrackets;
        this.wageOrders = wageOrders;
        this.locations = locations;
        this.payslips = payslips;
        this.audit = audit;
        this.settlement = settlement;
        this.bankSettlements = bankSettlements;
        this.peraTaxCredits = peraTaxCredits;
    }

    @Transactional
    public PayrollRun createRun(UUID payPeriodId, String runType) {
        String type = runType == null ? "REGULAR" : runType;
        if ("FINAL_PAY".equals(type)) {
            throw new BusinessException("Create final pay runs with POST /api/payroll/final-pay");
        }
        if (!RUN_TYPES.contains(type)) {
            throw new BusinessException("runType must be one of " + RUN_TYPES);
        }
        PayPeriod period = periods.lockForPayroll(payPeriodId).orElseThrow(() -> new NotFoundException("Pay period", payPeriodId));
        if ("LOCKED".equals(period.getStatus())) {
            throw new BusinessException("Pay period is locked");
        }
        if ("YEAR_END_ADJUSTMENT".equals(type)
                && (period.getEndDate().getMonthValue() != 12 || period.getEndDate().getDayOfMonth() != 31)) {
            throw new BusinessException("Year-end tax adjustments must use the final pay period ending December 31");
        }
        if ("YEAR_END_ADJUSTMENT".equals(type)
                && !runs.findByPayPeriodIdAndRunType(payPeriodId, "REGULAR").isEmpty()) {
            throw new BusinessException("Use the year-end adjustment run instead of a separate regular run for this period");
        }
        if ("REGULAR".equals(type)
                && !runs.findByPayPeriodIdAndRunType(payPeriodId, "YEAR_END_ADJUSTMENT").isEmpty()) {
            throw new BusinessException("A year-end adjustment run already exists for this period");
        }
        if (!"OFF_CYCLE".equals(type) && !runs.findByPayPeriodIdAndRunType(payPeriodId, type).isEmpty()) {
            throw new BusinessException("A " + type + " run already exists for this period");
        }
        PayrollRun run = new PayrollRun();
        run.setPayPeriodId(payPeriodId);
        run.setPayGroupId(period.getPayGroupId());
        run.setRunType(type);
        run = runs.save(run);
        audit.log("PayrollRun", run.getId(), "CREATE", type + " run for period " + period.getStartDate() + " to " + period.getEndDate());
        return run;
    }

    /** A settlement run for one separated employee, attached to an open pay period (usually the next payroll). */
    @Transactional
    public PayrollRun createFinalPayRun(UUID payPeriodId, UUID employeeId) {
        Employee emp = employees.findById(employeeId).orElseThrow(() -> new NotFoundException("Employee", employeeId));
        if (emp.getSeparationDate() == null) {
            throw new BusinessException("Set the employee's separation date first");
        }
        PayPeriod period = periods.lockForPayroll(payPeriodId).orElseThrow(() -> new NotFoundException("Pay period", payPeriodId));
        if ("LOCKED".equals(period.getStatus())) {
            throw new BusinessException("Pay period is locked");
        }
        if (period.getEndDate().isBefore(emp.getSeparationDate())) {
            throw new BusinessException("Final-pay period must end on or after the employee's separation date");
        }
        EmploymentRecord lastEmployment = employment
                .findFirstByEmployeeIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(employeeId, emp.getSeparationDate())
                .orElseThrow(() -> new BusinessException("No employment record is effective on the separation date"));
        if (!period.getPayGroupId().equals(lastEmployment.getPayGroupId())) {
            throw new BusinessException("Final-pay period must belong to the employee's pay group at separation");
        }
        if (!runs.findByEmployeeIdAndRunType(employeeId, "FINAL_PAY").isEmpty()) {
            throw new BusinessException("A final-pay run already exists for this employee");
        }
        PayrollRun run = new PayrollRun();
        run.setPayPeriodId(payPeriodId);
        run.setPayGroupId(period.getPayGroupId());
        run.setRunType("FINAL_PAY");
        run.setEmployeeId(employeeId);
        run = runs.save(run);
        audit.log("PayrollRun", run.getId(), "CREATE", "FINAL_PAY run for " + emp.getEmployeeNo());
        return run;
    }

    @Transactional
    public PayrollRun compute(UUID runId) {
        PayrollRun run = load(runId);
        requireStatus(run, "DRAFT", "COMPUTED", "REVIEWED");
        PayPeriod period = periods.findById(run.getPayPeriodId()).orElseThrow();
        PayGroup group = payGroups.findById(run.getPayGroupId()).orElseThrow();

        for (Adjustment a : adjustments.findByAppliedRunId(runId)) {
            a.setStatus("APPROVED");
            a.setAppliedRunId(null);
            adjustments.save(a);
        }
        lines.deleteByRunId(runId);
        payrollEmployees.deleteByRunId(runId);
        payrollEmployees.flush();

        Rules rules = SETTLEMENT_TYPES.contains(run.getRunType()) ? null : loadRules(group, period.getEndDate());
        if ("YEAR_END_ADJUSTMENT".equals(run.getRunType())) settlement.validateYearEnd(period, group);
        Map<UUID, EmploymentRecord> current = new LinkedHashMap<>();
        for (EmploymentRecord r : employment.findActiveInPayGroup(group.getId(), period.getStartDate(), period.getEndDate())) {
            current.merge(r.getEmployeeId(), r, (a, b) -> a.getEffectiveFrom().isAfter(b.getEffectiveFrom()) ? a : b);
        }

        int count = 0;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal deductions = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal employer = BigDecimal.ZERO;
        List<String> skipped = new ArrayList<>();

        List<PayrollEmployee> results = new ArrayList<>();
        if (SETTLEMENT_TYPES.contains(run.getRunType())) {
            results.addAll(settlement.compute(run, group));
        } else {
            for (Map.Entry<UUID, EmploymentRecord> e : current.entrySet()) {
                Employee emp = employees.findById(e.getKey()).orElse(null);
                if (emp == null || emp.getHireDate().isAfter(period.getEndDate())
                        || (emp.getSeparationDate() != null && emp.getSeparationDate().isBefore(period.getStartDate()))) {
                    continue;
                }
                PayrollEmployee computed = computeEmployee(run, period, group, rules, emp, e.getValue(), skipped);
                if (computed != null) {
                    results.add(computed);
                }
            }
        }
        for (PayrollEmployee pe : results) {
            count++;
            gross = gross.add(pe.getGross());
            deductions = deductions.add(pe.getTotalDeductions());
            net = net.add(pe.getNetPay());
            employer = employer.add(pe.getEmployerCost());
        }

        run.setEmployeeCount(count);
        run.setTotalGross(gross);
        run.setTotalDeductions(deductions);
        run.setTotalNet(net);
        run.setTotalEmployerCost(employer);
        run.setRemarks(skipped.isEmpty() ? null : "Skipped: " + String.join("; ", skipped));
        run.setStatus("COMPUTED");
        run.setComputedAt(Instant.now());
        audit.log("PayrollRun", runId, "COMPUTE", count + " employees, net " + net);
        return runs.save(run);
    }

    @Transactional
    public PayrollRun review(UUID runId) {
        return transition(runId, "COMPUTED", "REVIEWED", "REVIEW", null);
    }

    @Transactional
    public PayrollRun approve(UUID runId, UUID approverId) {
        PayrollRun run = load(runId);
        requireStatus(run, "REVIEWED");
        run.setApprovedBy(approverId);
        run.setApprovedAt(Instant.now());
        run.setStatus("APPROVED");
        audit.log("PayrollRun", runId, "APPROVE", "approved by " + approverId);
        return runs.save(run);
    }

    /** Issues employee-visible payslip snapshots and opens the acknowledgement window. */
    @Transactional
    public PayrollRun publishForEmployeeReview(UUID runId) {
        PayrollRun run = load(runId);
        requireStatus(run, "APPROVED");
        PayPeriod period = periods.findById(run.getPayPeriodId()).orElseThrow();
        List<PayrollEmployee> pes = payrollEmployees.findByRunId(runId);
        Map<UUID, List<PayrollLine>> byEmployee = new LinkedHashMap<>();
        for (PayrollLine line : lines.findByRunId(runId)) {
            byEmployee.computeIfAbsent(line.getPayrollEmployeeId(), key -> new ArrayList<>()).add(line);
        }
        for (PayrollEmployee pe : pes) {
            payslips.issue(run, period, pe, byEmployee.getOrDefault(pe.getId(), List.of()));
        }
        run.setStatus("EMPLOYEE_REVIEW");
        audit.log("PayrollRun", runId, "PUBLISH_FOR_EMPLOYEE_REVIEW", pes.size() + " payslips published");
        return runs.save(run);
    }

    /** Freezes an acknowledged run, reduces loan balances, and locks the DTR days that were paid. */
    @Transactional
    public PayrollRun lock(UUID runId) {
        PayrollRun run = load(runId);
        requireStatus(run, "EMPLOYEE_REVIEW");
        PayPeriod period = periods.findById(run.getPayPeriodId()).orElseThrow();
        List<PayrollEmployee> pes = payrollEmployees.findByRunId(runId);
        List<com.apex.hrm.payroll.domain.Payslip> employeeSlips = payslipsForRun(runId);
        if (employeeSlips.size() != pes.size() || employeeSlips.stream().anyMatch(slip -> slip.getAcknowledgedAt() == null)) {
            throw new BusinessException("Every employee must acknowledge their published payslip before payroll can be closed");
        }
        for (PayrollLine l : lines.findByRunId(runId)) {
            if ("LOAN".equals(l.getSourceType()) && l.getSourceId() != null) {
                Loan loan = loans.findById(l.getSourceId()).orElseThrow();
                loan.setBalance(loan.getBalance().subtract(l.getAmount()).max(BigDecimal.ZERO));
                if (loan.getBalance().signum() == 0) {
                    loan.setStatus("PAID");
                }
                loans.save(loan);
            }
        }
        for (PayrollEmployee pe : pes) {
            if (isRegularPeriodRun(run.getRunType())) {
                for (DtrDay d : dtrDays.findByEmployeeIdAndWorkDateBetween(pe.getEmployeeId(), period.getStartDate(), period.getEndDate())) {
                    d.setStatus("LOCKED");
                    dtrDays.save(d);
                }
            }
        }
        if (isRegularPeriodRun(run.getRunType())) {
            period.setStatus("LOCKED");
            periods.save(period);
        }
        run.setStatus("LOCKED");
        run.setLockedAt(Instant.now());
        audit.log("PayrollRun", runId, "LOCK", pes.size() + " employee payslips acknowledged; payroll closed");
        return runs.save(run);
    }

    private List<com.apex.hrm.payroll.domain.Payslip> payslipsForRun(UUID runId) {
        return payslips.findByRunId(runId);
    }

    public Map<String, Integer> employeeAcknowledgementCounts(UUID runId) {
        List<com.apex.hrm.payroll.domain.Payslip> slips = payslipsForRun(runId);
        int acknowledged = (int) slips.stream().filter(slip -> slip.getAcknowledgedAt() != null).count();
        return Map.of("acknowledged", acknowledged, "total", slips.size(), "pending", slips.size() - acknowledged);
    }

    @Transactional
    public PayrollRun markPaid(UUID runId) {
        PayrollRun run = runs.lockForSettlement(runId)
                .orElseThrow(() -> new NotFoundException("Payroll run", runId));
        requireStatus(run, "LOCKED");
        BigDecimal confirmed = bankSettlements.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .filter(row -> "CONFIRMED".equals(row.getStatus()))
                .map(row -> row.getAcknowledgedAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expected = run.getTotalNet().setScale(2, RoundingMode.HALF_UP);
        if (expected.signum() <= 0 || confirmed.compareTo(expected) != 0) {
            throw new BusinessException("Payroll cannot be marked PAID without fully confirmed bank settlement evidence");
        }
        run.setStatus("PAID");
        run.setPaidAt(Instant.now());
        audit.log("PayrollRun", runId, "PAID", "marked as paid");
        return runs.save(run);
    }

    // ---------------------------------------------------------------- computation

    private record Rules(Map<DayType, AttendancePayCalculator.PremiumRuleData> premium, SssCalculator.Rule sss,
                         PhilHealthCalculator.Rule philhealth, PagIbigCalculator.Rule pagibig,
                         List<WithholdingTaxCalculator.Bracket> brackets) {
    }

    private Rules loadRules(PayGroup group, LocalDate asOf) {
        Map<DayType, AttendancePayCalculator.PremiumRuleData> premium = new EnumMap<>(DayType.class);
        for (PremiumRule r : premiumRules.findByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(asOf)) {
            DayType t = DayType.valueOf(r.getDayType());
            premium.putIfAbsent(t, new AttendancePayCalculator.PremiumRuleData(t, r.getWorkedMultiplier(), r.getOtMultiplier(),
                    r.getUnworkedMultiplier(), Boolean.TRUE.equals(r.getBaseIncludedMonthly()), r.getNightDiffRate()));
        }
        if (!premium.containsKey(DayType.ORDINARY)) {
            throw new BusinessException("Premium rules are not configured (premium_rule)");
        }
        StatutoryContributionRule sss = statutoryRule("SSS", asOf);
        StatutoryContributionRule phic = statutoryRule("PHILHEALTH", asOf);
        StatutoryContributionRule hdmf = statutoryRule("PAGIBIG", asOf);

        List<WithholdingTaxBracket> all = taxBrackets.findByPeriodTypeAndEffectiveFromLessThanEqual(group.getFrequency(), asOf);
        LocalDate latest = all.stream().map(WithholdingTaxBracket::getEffectiveFrom).max(LocalDate::compareTo)
                .orElseThrow(() -> new BusinessException("No withholding tax table for " + group.getFrequency()));
        List<WithholdingTaxCalculator.Bracket> brackets = all.stream().filter(b -> b.getEffectiveFrom().equals(latest))
                .map(b -> new WithholdingTaxCalculator.Bracket(b.getLowerBound(), b.getBaseTax(), b.getRate(), b.getUpperBound())).toList();
        validateTaxBrackets(group.getFrequency(), brackets);

        return new Rules(premium,
                new SssCalculator.Rule(sss.getEmployeeRate(), sss.getEmployerRate(), sss.getMinBase(), sss.getMaxBase(),
                        sss.getMscStep(), sss.getEcLow(), sss.getEcHigh(), sss.getEcThreshold()),
                new PhilHealthCalculator.Rule(phic.getEmployeeRate(), phic.getEmployerRate(), phic.getMinBase(), phic.getMaxBase()),
                new PagIbigCalculator.Rule(hdmf.getEmployeeRate(), hdmf.getEmployerRate(), hdmf.getMaxBase(),
                        hdmf.getLowIncomeThreshold(), hdmf.getLowIncomeEmployeeRate()),
                brackets);
    }

    private StatutoryContributionRule statutoryRule(String agency, LocalDate asOf) {
        return statutory.findApplicable(agency, asOf).stream().findFirst()
                .orElseThrow(() -> new BusinessException("No " + agency + " contribution rule effective on " + asOf));
    }

    private List<WithholdingTaxCalculator.Bracket> annualTaxBrackets(LocalDate asOf) {
        List<WithholdingTaxBracket> all = taxBrackets.findByPeriodTypeAndEffectiveFromLessThanEqual("ANNUAL", asOf);
        LocalDate latest = all.stream().map(WithholdingTaxBracket::getEffectiveFrom).max(LocalDate::compareTo)
                .orElseThrow(() -> new BusinessException("No annual withholding tax table is configured"));
        List<WithholdingTaxCalculator.Bracket> brackets = all.stream().filter(b -> b.getEffectiveFrom().equals(latest))
                .map(b -> new WithholdingTaxCalculator.Bracket(b.getLowerBound(), b.getBaseTax(), b.getRate(), b.getUpperBound())).toList();
        validateTaxBrackets("ANNUAL", brackets);
        return brackets;
    }

    private static void validateTaxBrackets(String periodType, List<WithholdingTaxCalculator.Bracket> brackets) {
        List<WithholdingTaxCalculator.Bracket> ordered = brackets.stream()
                .sorted(java.util.Comparator.comparing(WithholdingTaxCalculator.Bracket::lowerBound)).toList();
        if (ordered.isEmpty() || ordered.get(0).lowerBound().signum() != 0) {
            throw new BusinessException("The " + periodType + " withholding table must begin at zero");
        }
        for (int i = 0; i < ordered.size(); i++) {
            WithholdingTaxCalculator.Bracket current = ordered.get(i);
            if (current.baseTax().signum() < 0 || current.rate().signum() < 0 || current.rate().compareTo(BigDecimal.ONE) > 0) {
                throw new BusinessException("The " + periodType + " withholding table contains an invalid tax amount or rate");
            }
            if (i == ordered.size() - 1) {
                if (current.upperBound() != null) {
                    throw new BusinessException("The " + periodType + " withholding table needs an open-ended final bracket");
                }
            } else if (current.upperBound() == null
                    || current.upperBound().compareTo(ordered.get(i + 1).lowerBound()) != 0) {
                throw new BusinessException("The " + periodType + " withholding table has a gap or overlap between brackets");
            }
        }
    }

    private PayrollEmployee computeEmployee(PayrollRun run, PayPeriod period, PayGroup group, Rules rules, Employee emp,
                                            EmploymentRecord rec, List<String> skipped) {
        LocalDate start = period.getStartDate();
        LocalDate end = period.getEndDate();
        Compensation comp = compensations
                .findFirstByEmployeeIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(emp.getId(), end).orElse(null);
        if (comp == null || (comp.getEffectiveTo() != null && comp.getEffectiveTo().isBefore(start))) {
            skipped.add(emp.getEmployeeNo() + " (no compensation record)");
            return null;
        }
        boolean yearEnd = "YEAR_END_ADJUSTMENT".equals(run.getRunType());
        boolean regular = "REGULAR".equals(run.getRunType()) || yearEnd;
        boolean thirteenth = "THIRTEENTH_MONTH".equals(run.getRunType());
        BigDecimal periodsPerMonth = periodsPerMonth(group);
        PayBasis basis = PayBasis.valueOf(comp.getPayBasis());
        if ("DAILY".equals(group.getFrequency()) && basis == PayBasis.MONTHLY) {
            throw new BusinessException("Daily payroll groups require daily-rate or hourly-rate compensation; use a monthly, semi-monthly, or weekly pay group for monthly salaries");
        }
        BigDecimal monthlyEquivalent = switch (basis) {
            case MONTHLY -> comp.getBaseRate();
            case DAILY -> comp.getBaseRate().multiply(group.getDaysFactor()).divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
            case HOURLY -> comp.getBaseRate().multiply(group.getHoursPerDay()).multiply(group.getDaysFactor())
                    .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
        };
        List<String> warnings = new ArrayList<>();

        PayrollEmployee pe = new PayrollEmployee();
        pe.setRunId(run.getId());
        pe.setEmployeeId(emp.getId());
        pe.setCostCenterId(rec.getCostCenterId());
        pe = payrollEmployees.save(pe);

        List<PayrollLine> out = new ArrayList<>();
        UUID cc = rec.getCostCenterId();

        if (thirteenth) {
            BigDecimal earned = lines.basicEarned(emp.getId(), LocalDate.of(end.getYear(), 1, 1), LocalDate.of(end.getYear(), 12, 31));
            BigDecimal yearStartPaid = lines.thirteenthPaid(emp.getId(), LocalDate.of(end.getYear(), 1, 1), end);
            BigDecimal yearStartExempt = lines.thirteenthExemptPaid(emp.getId(), LocalDate.of(end.getYear(), 1, 1), end);
            BigDecimal otherBenefits = lines.otherExemptBenefitsYtd(emp.getId(), LocalDate.of(end.getYear(), 1, 1), end);
            ThirteenthMonthCalculator.Result m13 = ThirteenthMonthCalculator.split(earned, yearStartPaid, yearStartExempt, otherBenefits);
            if (m13.exempt().signum() > 0) {
                out.add(line(run, pe, emp, cc, new PayLine("THIRTEENTH_MONTH", "13th month pay (tax-exempt part)", PayLine.EARNING, null, null, m13.exempt(), false), null, null));
            }
            if (m13.taxable().signum() > 0) {
                out.add(line(run, pe, emp, cc, new PayLine("THIRTEENTH_MONTH", "13th month pay (taxable excess)", PayLine.EARNING, null, null, m13.taxable(), true), null, null));
                warnings.add("Part of the 13th month pay is taxable; withholding on it is reconciled by the year-end adjustment run");
            }
            if (otherBenefits.signum() > 0) {
                warnings.add("Review exempt benefit classifications against the shared PHP 90,000 13th-month and other-benefits ceiling");
            }
        }

        if (regular) {
            BigDecimal periodBasic = basis == PayBasis.MONTHLY ? proratedBasic(emp, comp, start, end, periodsPerMonth) : null;
            AttendancePayCalculator.Config cfg = new AttendancePayCalculator.Config(basis, comp.getBaseRate(),
                    group.getDaysFactor(), group.getHoursPerDay(), periodsPerMonth, periodBasic);
            List<AttendancePayCalculator.DayInput> days = dtrDays.findByEmployeeIdAndWorkDateBetween(emp.getId(), start, end).stream()
                    .map(PayrollService::toDayInput).toList();
            if (days.isEmpty()) {
                warnings.add("No processed DTR for the period; run /api/dtr/process first");
            }
            for (PayLine l : AttendancePayCalculator.compute(cfg, rules.premium(), days)) {
                out.add(line(run, pe, emp, cc, l, null, null));
            }
            checkMinimumWage(emp, rec, cfg, end, warnings);
            for (RecurringItem r : recurring.findActive(emp.getId(), start, end)) {
                out.add(line(run, pe, emp, cc, new PayLine(r.getItemCode(), r.getDescription(), r.getCategory(), null, null,
                        Money.round(r.getAmount()), Boolean.TRUE.equals(r.getTaxable()), r.getIncomeClass()), "RECURRING", r.getId()));
            }
        }

        if (regular || "OFF_CYCLE".equals(run.getRunType())) {
            for (Adjustment a : adjustments.findByEmployeeIdAndStatus(emp.getId(), "APPROVED")) {
                if (a.getTargetPeriodId() == null || a.getTargetPeriodId().equals(period.getId())) {
                    out.add(line(run, pe, emp, cc, new PayLine(a.getItemCode(), a.getDescription(), a.getCategory(), null, null,
                            Money.round(a.getAmount()), Boolean.TRUE.equals(a.getTaxable()), a.getIncomeClass()), "ADJUSTMENT", a.getId()));
                    a.setStatus("APPLIED");
                    a.setAppliedRunId(run.getId());
                    adjustments.save(a);
                }
            }
        }

        BigDecimal contributionShare = contributionShare(group, period);
        if (regular && contributionShare.signum() > 0) {
            SssCalculator.Result sss = SssCalculator.monthly(rules.sss(), monthlyEquivalent);
            PhilHealthCalculator.Result phic = PhilHealthCalculator.monthly(rules.philhealth(), monthlyEquivalent);
            PagIbigCalculator.Result hdmf = PagIbigCalculator.monthly(rules.pagibig(), monthlyEquivalent);
            addShare(out, run, pe, emp, cc, "SSS_EE", "SSS contribution", PayLine.DEDUCTION, sss.employee(), contributionShare);
            addShare(out, run, pe, emp, cc, "PHIC_EE", "PhilHealth contribution", PayLine.DEDUCTION, phic.employee(), contributionShare);
            addShare(out, run, pe, emp, cc, "HDMF_EE", "Pag-IBIG contribution", PayLine.DEDUCTION, hdmf.employee(), contributionShare);
            addShare(out, run, pe, emp, cc, "SSS_ER", "SSS employer share", PayLine.EMPLOYER_COST, sss.employer(), contributionShare);
            addShare(out, run, pe, emp, cc, "SSS_EC", "SSS employees' compensation", PayLine.EMPLOYER_COST, sss.ec(), contributionShare);
            addShare(out, run, pe, emp, cc, "PHIC_ER", "PhilHealth employer share", PayLine.EMPLOYER_COST, phic.employer(), contributionShare);
            addShare(out, run, pe, emp, cc, "HDMF_ER", "Pag-IBIG employer share", PayLine.EMPLOYER_COST, hdmf.employer(), contributionShare);
        }

        BigDecimal taxableEarnings = sum(out, PayLine.EARNING, true, null);
        BigDecimal attendanceDeductions = sumCodes(out, ATTENDANCE_DEDUCTIONS);
        BigDecimal preTax = sumCodes(out, Set.of("SSS_EE", "PHIC_EE", "HDMF_EE"));
        BigDecimal taxable = taxableEarnings.subtract(attendanceDeductions).subtract(preTax).max(BigDecimal.ZERO);
        BigDecimal regularEarnings = out.stream().filter(l -> PayLine.EARNING.equals(l.getCategory()))
                .filter(l -> Boolean.TRUE.equals(l.getTaxable())).filter(l -> "REGULAR".equals(l.getIncomeClass()))
                .map(PayrollLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal supplementaryEarnings = out.stream().filter(l -> PayLine.EARNING.equals(l.getCategory()))
                .filter(l -> Boolean.TRUE.equals(l.getTaxable())).filter(l -> "SUPPLEMENTARY".equals(l.getIncomeClass()))
                .map(PayrollLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal reductionsAgainstRegular = attendanceDeductions.add(preTax).min(regularEarnings);
        BigDecimal regularTaxableIncome = regularEarnings.subtract(reductionsAgainstRegular).max(BigDecimal.ZERO);
        BigDecimal supplementaryTaxableIncome = supplementaryEarnings.subtract(attendanceDeductions.add(preTax)
                .subtract(reductionsAgainstRegular)).max(BigDecimal.ZERO);

        BigDecimal periodWithholding = BigDecimal.ZERO;
        if (regular) {
            BigDecimal tax = withholdingByIncomeClass(rules.brackets(), regularTaxableIncome, supplementaryTaxableIncome);
            periodWithholding = tax;
            if (tax.signum() > 0) {
                out.add(line(run, pe, emp, cc, new PayLine("WTAX", "Withholding tax", PayLine.DEDUCTION, null, null, tax, false), null, null));
            }
            for (Loan loan : loans.findByEmployeeIdAndStatus(emp.getId(), "ACTIVE")) {
                if (loan.getStartDate().isAfter(period.getEndDate())) {
                    continue;
                }
                BigDecimal due = Money.round(loan.getAmortization().min(loan.getBalance()));
                if (due.signum() > 0) {
                    out.add(line(run, pe, emp, cc, new PayLine("LOAN", loan.getLoanType() + " loan", PayLine.DEDUCTION, null, null, due, false),
                            "LOAN", loan.getId()));
                }
            }
        } else if ("OFF_CYCLE".equals(run.getRunType())) {
            warnings.add("Off-cycle run: no withholding tax is computed");
        }

        if (yearEnd) {
            LocalDate yearStart = LocalDate.of(end.getYear(), 1, 1);
            BigDecimal otherBenefitsYtd=lines.otherExemptBenefitsYtd(emp.getId(),yearStart,end.minusDays(1));
            BigDecimal currentOtherBenefits=out.stream().filter(PayrollService::usesSharedBenefitCeiling)
                    .map(PayrollLine::getAmount).reduce(BigDecimal.ZERO,BigDecimal::add);
            BigDecimal taxableExcessBenefits=otherBenefitsYtd.add(currentOtherBenefits)
                    .subtract(ThirteenthMonthCalculator.EXEMPT_CEILING).max(BigDecimal.ZERO);
            BigDecimal taxableYtd = payrollEmployees.taxableIncomeYtd(emp.getId(), yearStart, end.minusDays(1)).add(taxable)
                    .add(taxableExcessBenefits);
            taxableYtd = taxableYtd.add(priorYearTaxable(emp));
            BigDecimal withheldYtd = lines.taxWithheldYtd(emp.getId(), yearStart, end.minusDays(1)).add(periodWithholding);
            withheldYtd = withheldYtd.add(priorYearTaxWithheld(emp));
            List<WithholdingTaxCalculator.Bracket> brackets=annualTaxBrackets(end);
            BigDecimal annualTaxDue=AnnualTaxCalculator.taxDue(brackets,taxableYtd);
            BigDecimal certifiedPera=peraTaxCredits.findByEmployeeIdAndTaxYear(emp.getId(),end.getYear())
                    .filter(p->Boolean.TRUE.equals(p.getCertificateReceived())&&p.getCertificateNumber()!=null&&!p.getCertificateNumber().isBlank())
                    .map(p->p.getTaxCreditAmount()==null?BigDecimal.ZERO:p.getTaxCreditAmount()).orElse(BigDecimal.ZERO);
            BigDecimal peraApplied=certifiedPera.min(annualTaxDue.subtract(withheldYtd).max(BigDecimal.ZERO));
            BigDecimal adjustment = AnnualTaxCalculator.adjustment(brackets, taxableYtd, withheldYtd.add(peraApplied));
            if (adjustment.signum() > 0) {
                BigDecimal earningsAvailable = sum(out, PayLine.EARNING, false, null).subtract(attendanceDeductions);
                BigDecimal otherDeductions = sum(out, PayLine.DEDUCTION, false, null);
                BigDecimal availableForTax = earningsAvailable.subtract(otherDeductions).max(BigDecimal.ZERO);
                BigDecimal withheld = adjustment.min(availableForTax);
                if (withheld.signum() > 0) {
                    out.add(line(run, pe, emp, cc, new PayLine("WTAX", "Year-end annual tax adjustment", PayLine.DEDUCTION, null, null, withheld, false), null, null));
                }
                if (adjustment.compareTo(withheld) > 0) {
                    warnings.add("Additional annual tax of " + Money.round(adjustment.subtract(withheld))
                            + " could not be withheld from this pay; resolve the balance manually");
                }
            } else if (adjustment.signum() < 0) {
                BigDecimal refund = adjustment.negate();
                out.add(line(run, pe, emp, cc, new PayLine("TAX_REFUND", "Year-end refund of excess tax withheld", PayLine.EARNING, null, null, refund, false), null, null));
            }
        }

        BigDecimal earnings = sum(out, PayLine.EARNING, false, null);
        BigDecimal grossPay = earnings.subtract(attendanceDeductions);
        BigDecimal otherDeductions = sum(out, PayLine.DEDUCTION, false, null).subtract(attendanceDeductions);
        BigDecimal netPay = grossPay.subtract(otherDeductions);
        if (netPay.signum() < 0) {
            warnings.add("Negative net pay: deductions exceed earnings");
        }

        lines.saveAll(out);
        pe.setGross(grossPay);
        pe.setTaxableIncome(taxable);
        pe.setRegularTaxableIncome(regularTaxableIncome);
        pe.setSupplementaryTaxableIncome(supplementaryTaxableIncome);
        pe.setTotalDeductions(otherDeductions);
        pe.setNetPay(netPay);
        pe.setEmployerCost(sum(out, PayLine.EMPLOYER_COST, false, null));
        pe.setWarnings(warnings.isEmpty() ? null : String.join("; ", warnings));
        return payrollEmployees.save(pe);
    }

    /** Calendar-day proration for hires, separations and salary changes inside the period. */
    private BigDecimal proratedBasic(Employee emp, Compensation current, LocalDate start, LocalDate end, BigDecimal periodsPerMonth) {
        List<SalaryProration.Segment> segments = new ArrayList<>();
        LocalDate hire = emp.getHireDate();
        LocalDate separation = emp.getSeparationDate();
        for (Compensation c : compensations.findByEmployeeIdOrderByEffectiveFromDesc(emp.getId())) {
            if (!"MONTHLY".equals(c.getPayBasis())) {
                continue;
            }
            LocalDate from = c.getEffectiveFrom().isBefore(hire) ? hire : c.getEffectiveFrom();
            LocalDate to = c.getEffectiveTo();
            if (separation != null && (to == null || to.isAfter(separation))) {
                to = separation;
            }
            segments.add(new SalaryProration.Segment(c.getBaseRate(), from, to));
        }
        if (segments.isEmpty()) {
            segments.add(new SalaryProration.Segment(current.getBaseRate(), hire, separation));
        }
        return SalaryProration.periodBasic(segments, start, end, periodsPerMonth);
    }

    private static AttendancePayCalculator.DayInput toDayInput(DtrDay d) {
        return new AttendancePayCalculator.DayInput(d.getWorkDate(), DayType.valueOf(d.getDayType()), d.getRegularMinutes(),
                d.getLateMinutes(), d.getUndertimeMinutes(), d.getNightMinutes(), d.getNightOtMinutes(),
                Math.min(d.getOtDetectedMinutes(), d.getOtApprovedMinutes()), Boolean.TRUE.equals(d.getAbsent()),
                Boolean.TRUE.equals(d.getPaidLeave()), !Boolean.FALSE.equals(d.getHolidayPayEligible()));
    }

    /** Fraction of the monthly statutory contribution deducted in this cutoff. */
    private BigDecimal contributionShare(PayGroup group, PayPeriod period) {
        List<PayPeriod> monthlyPeriods = periods.findByPayGroupId(group.getId()).stream()
                .filter(candidate -> candidate.getPayDate().getYear() == period.getPayDate().getYear()
                        && candidate.getPayDate().getMonth() == period.getPayDate().getMonth())
                .toList();
        if ("EVERY_CUTOFF".equals(group.getContributionSchedule())) {
            int count = Math.max(monthlyPeriods.size(), 1);
            return BigDecimal.ONE.divide(BigDecimal.valueOf(count), 10, RoundingMode.HALF_UP);
        }
        boolean laterPayInMonth = monthlyPeriods.stream().anyMatch(candidate -> candidate.getPayDate().isAfter(period.getPayDate()));
        return laterPayInMonth ? BigDecimal.ZERO : BigDecimal.ONE;
    }

    private BigDecimal withholdingByIncomeClass(List<WithholdingTaxCalculator.Bracket> brackets,
                                                BigDecimal regularTaxable, BigDecimal supplementaryTaxable) {
        return WithholdingTaxCalculator.tax(brackets, regularTaxable)
                .add(WithholdingTaxCalculator.supplementaryTax(brackets, regularTaxable, supplementaryTaxable));
    }

    private static BigDecimal priorYearTaxable(Employee employee) {
        return employee.getPriorEmployerTaxableCompensation() == null ? BigDecimal.ZERO : employee.getPriorEmployerTaxableCompensation();
    }

    private static BigDecimal priorYearTaxWithheld(Employee employee) {
        return employee.getPriorEmployerTaxWithheld() == null ? BigDecimal.ZERO : employee.getPriorEmployerTaxWithheld();
    }

    private static BigDecimal periodsPerMonth(PayGroup group) {
        return switch (group.getFrequency()) {
            case "MONTHLY" -> BigDecimal.ONE;
            case "SEMI_MONTHLY" -> BigDecimal.valueOf(2);
            case "WEEKLY" -> BigDecimal.valueOf(52).divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP);
            case "DAILY" -> group.getDaysFactor().divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP);
            default -> throw new BusinessException("Unsupported pay frequency " + group.getFrequency());
        };
    }

    private void addShare(List<PayrollLine> out, PayrollRun run, PayrollEmployee pe, Employee emp, UUID cc, String code,
                          String description, String category, BigDecimal monthlyAmount, BigDecimal share) {
        BigDecimal amount = Money.round(monthlyAmount.multiply(share));
        if (amount.signum() > 0) {
            out.add(line(run, pe, emp, cc, new PayLine(code, description, category, null, null, amount, false), null, null));
        }
    }

    private void checkMinimumWage(Employee emp, EmploymentRecord rec, AttendancePayCalculator.Config cfg, LocalDate asOf,
                                  List<String> warnings) {
        if (rec.getWorkLocationId() == null) {
            return;
        }
        WorkLocation loc = locations.findById(rec.getWorkLocationId()).orElse(null);
        if (loc == null) {
            return;
        }
        String sector = loc.getWageSector() == null || loc.getWageSector().isBlank() ? "NON_AGRI" : loc.getWageSector();
        WageOrder wo = wageOrders.findApplicable(loc.getRegionCode(), sector, asOf).stream().findFirst().orElse(null);
        if (wo == null && Boolean.TRUE.equals(emp.getMinimumWageEarner())) {
            warnings.add("Employee is marked as a minimum-wage earner but no effective wage order is configured for "
                    + loc.getRegionCode() + " (" + sector + "); review wage-order coverage and tax eligibility");
        }
        if (wo != null) {
            int rateComparison = AttendancePayCalculator.dailyRate(cfg).compareTo(wo.getDailyRate());
            if (rateComparison < 0) {
                warnings.add("Daily rate is below the " + loc.getRegionCode() + " " + sector + " minimum wage of " + wo.getDailyRate().setScale(2, RoundingMode.HALF_UP) + "; review pay and any wage-distortion impact before finalizing the payroll");
            } else if (rateComparison > 0 && Boolean.TRUE.equals(emp.getMinimumWageEarner())) {
                warnings.add("Employee is marked as a minimum-wage earner but the daily rate exceeds the configured "
                        + loc.getRegionCode() + " minimum wage; review tax eligibility and the effective wage order");
            }
        }
    }

    private static PayrollLine line(PayrollRun run, PayrollEmployee pe, Employee emp, UUID costCenterId, PayLine l,
                                    String sourceType, UUID sourceId) {
        PayrollLine pl = new PayrollLine();
        pl.setRunId(run.getId());
        pl.setPayrollEmployeeId(pe.getId());
        pl.setEmployeeId(emp.getId());
        pl.setItemCode(l.code());
        pl.setDescription(l.description());
        pl.setCategory(l.category());
        pl.setIncomeClass(l.incomeClass());
        pl.setQuantity(l.quantity());
        pl.setRate(l.rate());
        pl.setAmount(l.amount());
        pl.setTaxable(l.taxable() && !(Boolean.TRUE.equals(emp.getMinimumWageEarner())
                && MWE_EXEMPT_ITEMS.contains(l.code())));
        pl.setSourceType(sourceType);
        pl.setSourceId(sourceId);
        pl.setCostCenterId(costCenterId);
        return pl;
    }

    private static boolean usesSharedBenefitCeiling(PayrollLine line) {
        return "EARNING".equals(line.getCategory()) && Boolean.FALSE.equals(line.getTaxable())
                && !Set.of("BASIC","HOLIDAY_PAY","PREMIUM","OT","OVERTIME","ND","NIGHT_DIFF","HAZARD_PAY",
                        "THIRTEENTH_MONTH","DE_MINIMIS","TAX_REFUND").contains(line.getItemCode());
    }

    private static BigDecimal sum(List<PayrollLine> list, String category, boolean taxableOnly, Set<String> codes) {
        return list.stream()
                .filter(l -> l.getCategory().equals(category))
                .filter(l -> !taxableOnly || Boolean.TRUE.equals(l.getTaxable()))
                .filter(l -> codes == null || codes.contains(l.getItemCode()))
                .map(PayrollLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sumCodes(List<PayrollLine> list, Set<String> codes) {
        return list.stream().filter(l -> codes.contains(l.getItemCode()))
                .map(PayrollLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ---------------------------------------------------------------- helpers

    private PayrollRun transition(UUID runId, String from, String to, String action, UUID actor) {
        PayrollRun run = load(runId);
        requireStatus(run, from);
        run.setStatus(to);
        audit.log("PayrollRun", runId, action, from + " -> " + to);
        return runs.save(run);
    }

    private PayrollRun load(UUID id) {
        return runs.lockForSettlement(id).orElseThrow(() -> new NotFoundException("Payroll run", id));
    }

    private static void requireStatus(PayrollRun run, String... allowed) {
        for (String s : allowed) {
            if (s.equals(run.getStatus())) {
                return;
            }
        }
        throw new BusinessException("Run is " + run.getStatus() + "; this action requires " + String.join(" or ", allowed));
    }

    private static boolean isRegularPeriodRun(String runType) {
        return "REGULAR".equals(runType) || "YEAR_END_ADJUSTMENT".equals(runType);
    }
}
