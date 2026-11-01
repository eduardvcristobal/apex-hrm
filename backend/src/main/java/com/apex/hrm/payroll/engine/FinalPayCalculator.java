package com.apex.hrm.payroll.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Settlement for a separated employee, computed after the last regular run is locked. Components: pro-rated 13th month,
 * conversion of unused convertible leave, annualized tax reconciliation, and recovery of outstanding loans.
 * Statutory contributions for the separation month are not computed here.
 */
public final class FinalPayCalculator {
    private FinalPayCalculator() {
    }

    public record LeaveBalance(String code, BigDecimal days) {
    }

    public record LoanBalance(String id, String name, BigDecimal balance) {
    }

    public record Input(BigDecimal basicEarnedYtd, BigDecimal thirteenthPaidYtd, BigDecimal thirteenthExemptPaidYtd,
                        BigDecimal otherExemptBenefitsYtd, BigDecimal dailyRate, List<LeaveBalance> unusedLeave,
                        BigDecimal ytdTaxable, BigDecimal ytdWithheld, List<LoanBalance> loans,
                        List<WithholdingTaxCalculator.Bracket> annualBrackets) {
        public Input(BigDecimal basicEarnedYtd, BigDecimal thirteenthPaidYtd, BigDecimal otherExemptBenefitsYtd,
                     BigDecimal dailyRate, List<LeaveBalance> unusedLeave, BigDecimal ytdTaxable, BigDecimal ytdWithheld,
                     List<LoanBalance> loans, List<WithholdingTaxCalculator.Bracket> annualBrackets) {
            this(basicEarnedYtd, thirteenthPaidYtd, BigDecimal.ZERO, otherExemptBenefitsYtd, dailyRate, unusedLeave,
                    ytdTaxable, ytdWithheld, loans, annualBrackets);
        }
    }

    /** loanRecoveries pairs loan ids with the amount to deduct; payroll lines for them carry the loan as their source. */
    public record Result(List<PayLine> lines, List<String[]> loanRecoveries, BigDecimal gross, BigDecimal deductions,
                         BigDecimal net, BigDecimal taxableIncome, List<String> warnings) {
    }

    public static Result compute(Input in) {
        List<PayLine> lines = new ArrayList<>();
        List<String[]> recoveries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal taxableFinal = BigDecimal.ZERO;

        ThirteenthMonthCalculator.Result m13 = ThirteenthMonthCalculator.split(in.basicEarnedYtd(), in.thirteenthPaidYtd(),
                in.thirteenthExemptPaidYtd(), in.otherExemptBenefitsYtd());
        if (m13.exempt().signum() > 0) {
            lines.add(new PayLine("THIRTEENTH_MONTH", "13th month pay, pro-rated (tax-exempt part)", PayLine.EARNING, null, null, m13.exempt(), false));
            gross = gross.add(m13.exempt());
        }
        if (m13.taxable().signum() > 0) {
            lines.add(new PayLine("THIRTEENTH_MONTH", "13th month pay, pro-rated (taxable excess)", PayLine.EARNING, null, null, m13.taxable(), true));
            gross = gross.add(m13.taxable());
            taxableFinal = taxableFinal.add(m13.taxable());
        }
        for (LeaveBalance lb : in.unusedLeave()) {
            BigDecimal amount = Money.round(lb.days().multiply(in.dailyRate()));
            if (amount.signum() > 0) {
                lines.add(new PayLine("LEAVE_CONVERSION", "Unused " + lb.code() + " leave, " + lb.days().stripTrailingZeros().toPlainString() + " days", PayLine.EARNING, lb.days(), in.dailyRate(), amount, true));
                gross = gross.add(amount);
                taxableFinal = taxableFinal.add(amount);
            }
        }
        warnings.add("Statutory contributions for the separation month are not included; review them manually");
        warnings.add("Review other exempt benefit classifications and prior-employer compensation before final tax approval");
        if (in.annualBrackets().isEmpty()) {
            warnings.add("Automatic annual tax reconciliation was skipped for a minimum-wage earner; review taxable benefits manually");
        }

        BigDecimal adjustment = in.annualBrackets().isEmpty() ? BigDecimal.ZERO
                : AnnualTaxCalculator.adjustment(in.annualBrackets(), in.ytdTaxable().add(taxableFinal), in.ytdWithheld());
        BigDecimal deductions = BigDecimal.ZERO;
        if (adjustment.signum() > 0) {
            BigDecimal withheld = adjustment.min(gross);
            if (withheld.signum() > 0) {
                lines.add(new PayLine("WTAX", "Withholding tax, annualized at separation", PayLine.DEDUCTION, null, null, withheld, false));
                deductions = deductions.add(withheld);
            }
            if (adjustment.compareTo(withheld) > 0) {
                warnings.add("Additional annual tax of " + Money.round(adjustment.subtract(withheld))
                        + " could not be withheld from this settlement; resolve the balance manually");
            }
        } else if (adjustment.signum() < 0) {
            BigDecimal refund = adjustment.negate();
            lines.add(new PayLine("TAX_REFUND", "Refund of excess tax withheld", PayLine.EARNING, null, null, refund, false));
            gross = gross.add(refund);
        }

        BigDecimal available = gross.subtract(deductions).max(BigDecimal.ZERO);
        BigDecimal unrecovered = BigDecimal.ZERO;
        for (LoanBalance l : in.loans()) {
            BigDecimal take = l.balance().min(available);
            if (take.signum() > 0) {
                lines.add(new PayLine("LOAN", l.name() + ", balance recovery", PayLine.DEDUCTION, null, null, Money.round(take), false));
                recoveries.add(new String[]{l.id(), Money.round(take).toPlainString()});
                deductions = deductions.add(take);
                available = available.subtract(take);
            }
            unrecovered = unrecovered.add(l.balance().subtract(take));
        }
        if (unrecovered.signum() > 0) {
            warnings.add("Loan balance of " + Money.round(unrecovered).toPlainString() + " could not be recovered from this settlement");
        }
        return new Result(lines, recoveries, Money.round(gross), Money.round(deductions), Money.round(gross.subtract(deductions)),
                Money.round(taxableFinal), warnings);
    }
}
