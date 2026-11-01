package com.apex.hrm.payroll.service;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.payroll.domain.PayGroup;
import com.apex.hrm.payroll.domain.PayPeriod;
import com.apex.hrm.payroll.domain.PayrollEmployee;
import com.apex.hrm.payroll.domain.PayrollLine;
import com.apex.hrm.payroll.domain.PayrollRun;
import com.apex.hrm.payroll.domain.WithholdingTaxBracket;
import com.apex.hrm.payroll.engine.FinalPayCalculator;
import com.apex.hrm.payroll.engine.Money;
import com.apex.hrm.payroll.engine.PayLine;
import com.apex.hrm.payroll.engine.WithholdingTaxCalculator;
import com.apex.hrm.payroll.repo.PayPeriodRepository;
import com.apex.hrm.payroll.repo.PayrollEmployeeRepository;
import com.apex.hrm.payroll.repo.PayrollLineRepository;
import com.apex.hrm.payroll.repo.PayrollRunRepository;
import com.apex.hrm.payroll.repo.WithholdingTaxBracketRepository;
import com.apex.hrm.personnel.domain.Compensation;
import com.apex.hrm.personnel.domain.Employee;
import com.apex.hrm.personnel.domain.EmploymentRecord;
import com.apex.hrm.personnel.domain.Loan;
import com.apex.hrm.personnel.repo.CompensationRepository;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import com.apex.hrm.personnel.repo.EmploymentRecordRepository;
import com.apex.hrm.personnel.repo.LoanRepository;
import com.apex.hrm.timekeeping.domain.LeaveType;
import com.apex.hrm.timekeeping.repo.LeaveLedgerRepository;
import com.apex.hrm.timekeeping.repo.LeaveTypeRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes FINAL_PAY for one separated employee. The year-end adjustment is combined with the final regular payroll
 * in PayrollService so its tax adjustment is applied against actual earnings.
 * Results are saved as ordinary payroll employees and lines, so review, approval, locking, payslips,
 * journal entries and bank files work the same way as for a regular run.
 */
@Service
public class SettlementService {
    private final EmployeeRepository employees;
    private final CompensationRepository compensations;
    private final EmploymentRecordRepository employment;
    private final LeaveTypeRepository leaveTypes;
    private final LeaveLedgerRepository ledger;
    private final LoanRepository loans;
    private final PayPeriodRepository periods;
    private final PayrollEmployeeRepository payrollEmployees;
    private final PayrollLineRepository lines;
    private final PayrollRunRepository runs;
    private final WithholdingTaxBracketRepository taxBrackets;

    public SettlementService(EmployeeRepository employees, CompensationRepository compensations,
                             EmploymentRecordRepository employment, LeaveTypeRepository leaveTypes,
                             LeaveLedgerRepository ledger, LoanRepository loans, PayPeriodRepository periods,
                             PayrollEmployeeRepository payrollEmployees, PayrollLineRepository lines, PayrollRunRepository runs,
                             WithholdingTaxBracketRepository taxBrackets) {
        this.employees = employees;
        this.compensations = compensations;
        this.employment = employment;
        this.leaveTypes = leaveTypes;
        this.ledger = ledger;
        this.loans = loans;
        this.periods = periods;
        this.payrollEmployees = payrollEmployees;
        this.lines = lines;
        this.runs = runs;
        this.taxBrackets = taxBrackets;
    }

    @Transactional
    public List<PayrollEmployee> compute(PayrollRun run, PayGroup group) {
        if (!"FINAL_PAY".equals(run.getRunType())) {
            throw new BusinessException("Unsupported settlement run type: " + run.getRunType());
        }
        return finalPay(run, group);
    }

    public void validateYearEnd(PayPeriod currentPeriod, PayGroup group) {
        LocalDate from = LocalDate.of(currentPeriod.getEndDate().getYear(), 1, 1);
        LocalDate end = currentPeriod.getEndDate();
        for (PayPeriod ytdPeriod : periods.findByPayGroupId(group.getId())) {
            if (ytdPeriod.getEndDate().isBefore(from) || ytdPeriod.getEndDate().isAfter(end)) continue;
            if (!ytdPeriod.getId().equals(currentPeriod.getId())) {
                boolean regularSettled = runs.findByPayPeriodIdAndRunType(ytdPeriod.getId(), "REGULAR").stream()
                        .anyMatch(r -> "LOCKED".equals(r.getStatus()) || "PAID".equals(r.getStatus()));
                if (!regularSettled) {
                    throw new BusinessException("Complete and lock every prior regular pay period through " + end.getYear()
                            + " before computing the year-end tax adjustment");
                }
            }
            for (String type : List.of("THIRTEENTH_MONTH", "FINAL_PAY")) {
                boolean pending = runs.findByPayPeriodIdAndRunType(ytdPeriod.getId(), type).stream()
                        .anyMatch(r -> !"LOCKED".equals(r.getStatus()) && !"PAID".equals(r.getStatus()));
                if (pending) throw new BusinessException("Complete all " + type.replace('_', ' ').toLowerCase()
                        + " runs through " + end.getYear() + " before computing the year-end adjustment");
            }
        }
    }

    private List<PayrollEmployee> finalPay(PayrollRun run, PayGroup group) {
        Employee emp = employees.findById(run.getEmployeeId()).orElseThrow(() -> new NotFoundException("Employee", run.getEmployeeId()));
        LocalDate sep = emp.getSeparationDate();
        if (sep == null) {
            throw new BusinessException("Set the employee's separation date before computing final pay");
        }
        for (PayPeriod ytdPeriod : periods.findByPayGroupId(group.getId())) {
            if (ytdPeriod.getEndDate().getYear() != sep.getYear()) continue;
            boolean pendingThirteenth = runs.findByPayPeriodIdAndRunType(ytdPeriod.getId(), "THIRTEENTH_MONTH").stream()
                    .anyMatch(r -> !"LOCKED".equals(r.getStatus()) && !"PAID".equals(r.getStatus()));
            if (pendingThirteenth) {
                throw new BusinessException("Complete any 13th-month run for " + sep.getYear() + " before computing final pay");
            }
        }
        boolean closed = periods.findByPayGroupId(group.getId()).stream().anyMatch(p ->
                !sep.isBefore(p.getStartDate()) && !sep.isAfter(p.getEndDate()) && "LOCKED".equals(p.getStatus()));
        if (!closed) {
            throw new BusinessException("Lock the regular payroll period that contains the separation date " + sep + " first");
        }
        Compensation comp = compensations.findFirstByEmployeeIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(emp.getId(), sep)
                .orElseThrow(() -> new BusinessException("No compensation record effective on " + sep));
        BigDecimal dailyRate = "DAILY".equals(comp.getPayBasis()) ? comp.getBaseRate()
                : comp.getBaseRate().multiply(BigDecimal.valueOf(12)).divide(group.getDaysFactor(), 6, RoundingMode.HALF_UP);
        LocalDate from = LocalDate.of(sep.getYear(), 1, 1);
        LocalDate to = LocalDate.of(sep.getYear(), 12, 31);

        List<FinalPayCalculator.LeaveBalance> leave = new ArrayList<>();
        for (LeaveType t : leaveTypes.findAll()) {
            if (Boolean.TRUE.equals(t.getConvertible()) && Boolean.TRUE.equals(t.getPaid())) {
                BigDecimal days = ledger.balance(emp.getId(), t.getId());
                if (days != null && days.signum() > 0) {
                    leave.add(new FinalPayCalculator.LeaveBalance(t.getCode(), days));
                }
            }
        }
        List<FinalPayCalculator.LoanBalance> loanBalances = new ArrayList<>();
        for (Loan l : loans.findByEmployeeIdAndStatus(emp.getId(), "ACTIVE")) {
            if (l.getBalance().signum() > 0) {
                loanBalances.add(new FinalPayCalculator.LoanBalance(l.getId().toString(), l.getLoanType(), l.getBalance()));
            }
        }
        BigDecimal otherBenefits = lines.otherExemptBenefitsYtd(emp.getId(), from, to);
        List<WithholdingTaxCalculator.Bracket> brackets = Boolean.TRUE.equals(emp.getMinimumWageEarner())
                ? List.of() : annualBrackets(sep);
        FinalPayCalculator.Result r = FinalPayCalculator.compute(new FinalPayCalculator.Input(
                lines.basicEarned(emp.getId(), from, to), lines.thirteenthPaid(emp.getId(), from, to),
                lines.thirteenthExemptPaid(emp.getId(), from, to), otherBenefits, dailyRate,
                leave, payrollEmployees.taxableIncomeYtd(emp.getId(), from, to), lines.taxWithheldYtd(emp.getId(), from, to),
                loanBalances, brackets));

        UUID costCenter = employment.findFirstByEmployeeIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(emp.getId(), sep)
                .map(EmploymentRecord::getCostCenterId).orElse(null);
        PayrollEmployee pe = newPayrollEmployee(run, emp, costCenter);
        List<PayrollLine> out = new ArrayList<>();
        int loanIndex = 0;
        for (PayLine l : r.lines()) {
            String sourceType = null;
            UUID sourceId = null;
            if ("LOAN".equals(l.code()) && loanIndex < r.loanRecoveries().size()) {
                sourceType = "LOAN";
                sourceId = UUID.fromString(r.loanRecoveries().get(loanIndex++)[0]);
            }
            out.add(line(run, pe, emp, costCenter, l, sourceType, sourceId));
        }
        lines.saveAll(out);
        pe.setGross(r.gross());
        pe.setTaxableIncome(r.taxableIncome());
        pe.setTotalDeductions(r.deductions());
        pe.setNetPay(r.net());
        pe.setWarnings(String.join("; ", r.warnings()));
        return List.of(payrollEmployees.save(pe));
    }

    private List<WithholdingTaxCalculator.Bracket> annualBrackets(LocalDate asOf) {
        List<WithholdingTaxBracket> all = taxBrackets.findByPeriodTypeAndEffectiveFromLessThanEqual("ANNUAL", asOf);
        LocalDate latest = all.stream().map(WithholdingTaxBracket::getEffectiveFrom).max(LocalDate::compareTo)
                .orElseThrow(() -> new BusinessException("No annual withholding tax table is configured"));
        return all.stream().filter(b -> b.getEffectiveFrom().equals(latest))
                .map(b -> new WithholdingTaxCalculator.Bracket(b.getLowerBound(), b.getBaseTax(), b.getRate())).toList();
    }

    private PayrollEmployee newPayrollEmployee(PayrollRun run, Employee emp, UUID costCenterId) {
        PayrollEmployee pe = new PayrollEmployee();
        pe.setRunId(run.getId());
        pe.setEmployeeId(emp.getId());
        pe.setCostCenterId(costCenterId);
        return payrollEmployees.save(pe);
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
}
