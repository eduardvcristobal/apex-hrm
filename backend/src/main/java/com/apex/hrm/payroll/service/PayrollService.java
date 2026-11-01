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
import com.apex.hrm.payroll.engine.CutoffProrationCalculator;
import com.apex.hrm.payroll.engine.Money;
import com.apex.hrm.payroll.engine.PagIbigCalculator;
import com.apex.hrm.payroll.engine.PayBasis;
import com.apex.hrm.payroll.engine.PayLine;
import com.apex.hrm.payroll.engine.PhilHealthCalculator;
import com.apex.hrm.payroll.engine.SssCalculator;
import com.apex.hrm.payroll.engine.WithholdingTaxCalculator;
import com.apex.hrm.payroll.repo.AdjustmentRepository;
import com.apex.hrm.payroll.repo.PayGroupRepository;
import com.apex.hrm.payroll.repo.PayPeriodRepository;
import com.apex.hrm.payroll.repo.PayrollEmployeeRepository;
import com.apex.hrm.payroll.repo.PayrollLineRepository;
import com.apex.hrm.payroll.repo.PayrollRunRepository;
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
    private static final Set<String> ATTENDANCE_DEDUCTIONS = Set.of("ABSENCE", "LATE_UT", "PRORATION");
    private static final Set<String> RUN_TYPES = Set.of("REGULAR", "OFF_CYCLE", "THIRTEENTH_MONTH");
    private static final BigDecimal THIRTEENTH_MONTH_EXEMPT_CAP = new BigDecimal("90000");

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

    public PayrollService(PayrollRunRepository runs, PayPeriodRepository periods, PayGroupRepository payGroups,
                          PayrollEmployeeRepository payrollEmployees, PayrollLineRepository lines,
                          AdjustmentRepository adjustments, EmploymentRecordRepository employment,
                          EmployeeRepository employees, CompensationRepository compensations, DtrDayRepository dtrDays,
                          RecurringItemRepository recurring, LoanRepository loans, PremiumRuleRepository premiumRules,
                          StatutoryContributionRuleRepository statutory, WithholdingTaxBracketRepository taxBrackets,
                          WageOrderRepository wageOrders, WorkLocationRepository locations, PayslipService payslips,
                          AuditService audit) {
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
    }

    @Transactional
    public PayrollRun createRun(UUID payPeriodId, String runType) {
        String type = runType == null ? "REGULAR" : runType;
        if (!RUN_TYPES.contains(type)) {
            throw new BusinessException("runType must be one of " + RUN_TYPES);
        }
        PayPeriod period = periods.findById(payPeriodId).orElseThrow(() -> new NotFoundException("Pay period", payPeriodId));
        if ("LOCKED".equals(period.getStatus())) {
            throw new BusinessException("Pay period is locked");
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

        Rules rules = loadRules(group, period.getEndDate());
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

        for (Map.Entry<UUID, EmploymentRecord> e : current.entrySet()) {
            Employee emp = employees.findById(e.getKey()).orElse(null);
            if (emp == null || emp.getHireDate().isAfter(period.getEndDate())
                    || (emp.getSeparationDate() != null && emp.getSeparationDate().isBefore(period.getStartDate()))) {
                continue;
            }
            PayrollEmployee pe = computeEmployee(run, period, group, rules, emp, e.getValue(), skipped);
            if (pe == null) {
                continue;
            }
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

    /** Freezes the period: issues payslips, reduces loan balances and locks the DTR days that were paid. */
    @Transactional
    public PayrollRun lock(UUID runId) {
        PayrollRun run = load(runId);
        requireStatus(run, "APPROVED");
        PayPeriod period = periods.findById(run.getPayPeriodId()).orElseThrow();
        List<PayrollEmployee> pes = payrollEmployees.findByRunId(runId);
        Map<UUID, List<PayrollLine>> byEmployee = new LinkedHashMap<>();
        for (PayrollLine l : lines.findByRunId(runId)) {
            byEmployee.computeIfAbsent(l.getPayrollEmployeeId(), k -> new ArrayList<>()).add(l);
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
            payslips.issue(run, period, pe, byEmployee.getOrDefault(pe.getId(), List.of()));
            if ("REGULAR".equals(run.getRunType())) {
                for (DtrDay d : dtrDays.findByEmployeeIdAndWorkDateBetween(pe.getEmployeeId(), period.getStartDate(), period.getEndDate())) {
                    d.setStatus("LOCKED");
                    dtrDays.save(d);
                }
            }
        }
        if ("REGULAR".equals(run.getRunType())) {
            period.setStatus("LOCKED");
            periods.save(period);
        }
        run.setStatus("LOCKED");
        run.setLockedAt(Instant.now());
        audit.log("PayrollRun", runId, "LOCK", pes.size() + " payslips issued");
        return runs.save(run);
    }

    @Transactional
    public PayrollRun markPaid(UUID runId) {
        PayrollRun run = load(runId);
        requireStatus(run, "LOCKED");
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
                .map(b -> new WithholdingTaxCalculator.Bracket(b.getLowerBound(), b.getBaseTax(), b.getRate())).toList();

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
        boolean regular = "REGULAR".equals(run.getRunType());
        boolean thirteenth = "THIRTEENTH_MONTH".equals(run.getRunType());
        int periodsPerMonth = "MONTHLY".equals(group.getFrequency()) ? 1 : 2;
        PayBasis basis = PayBasis.valueOf(comp.getPayBasis());
        BigDecimal monthlyEquivalent = basis == PayBasis.MONTHLY ? comp.getBaseRate()
                : comp.getBaseRate().multiply(group.getDaysFactor()).divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
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
            BigDecimal amount = Money.round(earned.divide(BigDecimal.valueOf(12), 6, RoundingMode.HALF_UP));
            if (amount.signum() > 0) {
                out.add(line(run, pe, emp, cc, new PayLine("THIRTEENTH_MONTH", "13th month pay", PayLine.EARNING, null, null, amount, false), null, null));
            }
            if (amount.compareTo(THIRTEENTH_MONTH_EXEMPT_CAP) > 0) {
                warnings.add("13th month exceeds 90,000; the taxable excess (with other benefits) is not computed here");
            }
        }

        if (regular) {
            AttendancePayCalculator.Config cfg = new AttendancePayCalculator.Config(basis, comp.getBaseRate(),
                    group.getDaysFactor(), group.getHoursPerDay(), periodsPerMonth);
            List<AttendancePayCalculator.DayInput> days = dtrDays.findByEmployeeIdAndWorkDateBetween(emp.getId(), start, end).stream()
                    .map(PayrollService::toDayInput).toList();
            if (days.isEmpty()) {
                warnings.add("No processed DTR for the period; run /api/dtr/process first");
            }
            List<PayLine> calculated = AttendancePayCalculator.compute(cfg, rules.premium(), days);
            for (PayLine l : calculated) {
                out.add(line(run, pe, emp, cc, l, null, null));
            }
            if (basis == PayBasis.MONTHLY) {
                // Assignment transfers must not reduce an employee's contractual cutoff salary.
                // True employment dates determine partial cutoff eligibility.
                LocalDate activeFrom = emp.getHireDate();
                LocalDate activeThrough = emp.getSeparationDate();
                LocalDate firstEligible = activeFrom.isAfter(start) ? activeFrom : start;
                // A compensation change during an active cutoff requires split-rate processing;
                // fail closed rather than overpaying or underpaying at the last known rate.
                if (comp.getEffectiveFrom() != null && comp.getEffectiveFrom().isAfter(firstEligible)
                        || comp.getEffectiveTo() != null && comp.getEffectiveTo().isBefore(end)
                        && (activeThrough == null || comp.getEffectiveTo().isBefore(activeThrough))) {
                    throw new BusinessException("Mid-cutoff compensation change for "
                            + emp.getEmployeeNo() + " requires split-rate reconciliation");
                }
                BigDecimal basic = calculated.stream().filter(l -> "BASIC".equals(l.code()))
                        .map(PayLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal proration = CutoffProrationCalculator.deduction(basic, start, end, activeFrom, activeThrough);
                if (proration.signum() > 0) {
                    out.add(line(run, pe, emp, cc, new PayLine("PRORATION",
                            "Partial-cutoff basic pay adjustment (calendar days)", PayLine.DEDUCTION,
                            null, null, proration, true), null, null));
                }
            }
            checkMinimumWage(emp, rec, cfg, end, warnings);
            for (RecurringItem r : recurring.findActive(emp.getId(), start, end)) {
                out.add(line(run, pe, emp, cc, new PayLine(r.getItemCode(), r.getDescription(), r.getCategory(), null, null,
                        Money.round(r.getAmount()), Boolean.TRUE.equals(r.getTaxable())), "RECURRING", r.getId()));
            }
        }

        if (regular || "OFF_CYCLE".equals(run.getRunType())) {
            for (Adjustment a : adjustments.findByEmployeeIdAndStatus(emp.getId(), "APPROVED")) {
                if (a.getTargetPeriodId() == null || a.getTargetPeriodId().equals(period.getId())) {
                    out.add(line(run, pe, emp, cc, new PayLine(a.getItemCode(), a.getDescription(), a.getCategory(), null, null,
                            Money.round(a.getAmount()), Boolean.TRUE.equals(a.getTaxable())), "ADJUSTMENT", a.getId()));
                    a.setStatus("APPLIED");
                    a.setAppliedRunId(run.getId());
                    adjustments.save(a);
                }
            }
        }

        BigDecimal contributionShare = contributionShare(group, period, periodsPerMonth);
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

        if (regular) {
            BigDecimal tax = Boolean.TRUE.equals(emp.getMinimumWageEarner()) ? Money.ZERO
                    : WithholdingTaxCalculator.tax(rules.brackets(), taxable);
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
        pe.setTotalDeductions(otherDeductions);
        pe.setNetPay(netPay);
        pe.setEmployerCost(sum(out, PayLine.EMPLOYER_COST, false, null));
        pe.setWarnings(warnings.isEmpty() ? null : String.join("; ", warnings));
        return payrollEmployees.save(pe);
    }

    private static AttendancePayCalculator.DayInput toDayInput(DtrDay d) {
        return new AttendancePayCalculator.DayInput(d.getWorkDate(), DayType.valueOf(d.getDayType()), d.getRegularMinutes(),
                d.getLateMinutes(), d.getUndertimeMinutes(), d.getNightMinutes(), d.getNightOtMinutes(),
                Math.min(d.getOtDetectedMinutes(), d.getOtApprovedMinutes()), Boolean.TRUE.equals(d.getAbsent()),
                Boolean.TRUE.equals(d.getPaidLeave()));
    }

    /** Fraction of the monthly statutory contribution deducted in this cutoff. */
    private static BigDecimal contributionShare(PayGroup group, PayPeriod period, int periodsPerMonth) {
        if ("EVERY_CUTOFF".equals(group.getContributionSchedule())) {
            return BigDecimal.ONE.divide(BigDecimal.valueOf(periodsPerMonth), 10, RoundingMode.HALF_UP);
        }
        return period.getPeriodOfMonth() == periodsPerMonth ? BigDecimal.ONE : BigDecimal.ZERO;
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
        WageOrder wo = wageOrders.findApplicable(loc.getRegionCode(), "NON_AGRI", asOf).stream().findFirst().orElse(null);
        if (wo != null && AttendancePayCalculator.dailyRate(cfg).compareTo(wo.getDailyRate()) < 0) {
            warnings.add("Daily rate is below the " + loc.getRegionCode() + " minimum wage of " + wo.getDailyRate().setScale(2, RoundingMode.HALF_UP));
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
        pl.setQuantity(l.quantity());
        pl.setRate(l.rate());
        pl.setAmount(l.amount());
        pl.setTaxable(l.taxable());
        pl.setSourceType(sourceType);
        pl.setSourceId(sourceId);
        pl.setCostCenterId(costCenterId);
        return pl;
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
        return runs.findById(id).orElseThrow(() -> new NotFoundException("Payroll run", id));
    }

    private static void requireStatus(PayrollRun run, String... allowed) {
        for (String s : allowed) {
            if (s.equals(run.getStatus())) {
                return;
            }
        }
        throw new BusinessException("Run is " + run.getStatus() + "; this action requires " + String.join(" or ", allowed));
    }
}
