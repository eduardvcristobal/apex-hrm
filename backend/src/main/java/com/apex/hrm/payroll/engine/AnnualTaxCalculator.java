package com.apex.hrm.payroll.engine;

import java.math.BigDecimal;
import java.util.List;

/** Year-end reconciliation of withholding tax against the annual table. Positive = employee owes, negative = refund. */
public final class AnnualTaxCalculator {
    private AnnualTaxCalculator() {
    }

    public static BigDecimal taxDue(List<WithholdingTaxCalculator.Bracket> annualBrackets, BigDecimal annualTaxable) {
        return WithholdingTaxCalculator.tax(annualBrackets, annualTaxable);
    }

    public static BigDecimal adjustment(List<WithholdingTaxCalculator.Bracket> annualBrackets, BigDecimal annualTaxable, BigDecimal withheldYtd) {
        return Money.round(taxDue(annualBrackets, annualTaxable).subtract(withheldYtd));
    }
}
