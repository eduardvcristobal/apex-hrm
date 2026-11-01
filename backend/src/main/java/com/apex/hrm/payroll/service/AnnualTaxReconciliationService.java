package com.apex.hrm.payroll.service;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.payroll.domain.PayrollLine;
import com.apex.hrm.payroll.domain.WithholdingTaxBracket;
import com.apex.hrm.payroll.engine.AnnualCompensationLedger;
import com.apex.hrm.payroll.engine.AnnualizedTaxCalculator;
import com.apex.hrm.payroll.engine.WithholdingTaxCalculator;
import com.apex.hrm.payroll.repo.PayrollLineRepository;
import com.apex.hrm.payroll.repo.WithholdingTaxBracketRepository;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reconciles persisted, locked payroll records; never changes payroll or books refunds. */
@Service
public class AnnualTaxReconciliationService {
    private static final BigDecimal BENEFIT_CAP = new BigDecimal("90000");
    private final PayrollLineRepository lines;
    private final WithholdingTaxBracketRepository brackets;
    private final EmployeeRepository employees;

    public AnnualTaxReconciliationService(PayrollLineRepository lines,
            WithholdingTaxBracketRepository brackets, EmployeeRepository employees) {
        this.lines = lines;
        this.brackets = brackets;
        this.employees = employees;
    }

    public record Reconciliation(UUID employeeId, int taxYear, int lineCount,
            AnnualCompensationLedger.Totals ledger, AnnualizedTaxCalculator.Result tax,
            String certificationStatus) {}

    @Transactional(readOnly = true)
    public Reconciliation preview(UUID employeeId, int year) {
        if (year < 2023 || year > LocalDate.now().getYear())
            throw new BusinessException("Tax year must be between 2023 and the current year");
        if (!employees.existsById(employeeId))
            throw new NotFoundException("Employee", employeeId);
        LocalDate end = LocalDate.of(year, 12, 31);
        List<PayrollLine> recorded = lines.lockedLinesForTaxYear(employeeId,
                LocalDate.of(year, 1, 1), end);
        if (recorded.isEmpty()) throw new BusinessException("No locked or paid payroll for this year");
        List<WithholdingTaxBracket> available = brackets
                .findByPeriodTypeAndEffectiveFromLessThanEqual("ANNUAL", end);
        LocalDate applicable = available.stream().map(WithholdingTaxBracket::getEffectiveFrom)
                .max(LocalDate::compareTo).orElseThrow(() ->
                        new BusinessException("No applicable BIR annual tax brackets"));
        List<WithholdingTaxCalculator.Bracket> taxRates = available.stream()
                .filter(b -> applicable.equals(b.getEffectiveFrom()))
                .map(b -> new WithholdingTaxCalculator.Bracket(
                        b.getLowerBound(), b.getBaseTax(), b.getRate())).toList();
        var items = recorded.stream().map(l -> new AnnualCompensationLedger.Item(
                l.getItemCode(), l.getCategory(), l.getAmount(), Boolean.TRUE.equals(l.getTaxable()))).toList();
        var sums = AnnualCompensationLedger.aggregate(items);
        var tax = AnnualizedTaxCalculator.calculate(new AnnualizedTaxCalculator.Input(
                sums.regularTaxable(), sums.thirteenthMonth(), sums.otherBenefits(),
                BENEFIT_CAP, sums.withheld(), taxRates));
        return new Reconciliation(employeeId, year, recorded.size(), sums, tax,
                "REVIEW_REQUIRED: source ledger needs benefit classification, complete-year and HR/BIR approval");
    }
}
