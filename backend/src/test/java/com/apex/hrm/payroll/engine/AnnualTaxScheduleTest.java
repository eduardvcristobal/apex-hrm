package com.apex.hrm.payroll.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Regression values derived from the BIR annual table effective January 2023. */
class AnnualTaxScheduleTest {
    private static BigDecimal bd(String n) { return new BigDecimal(n); }
    private static final List<WithholdingTaxCalculator.Bracket> BRACKETS = List.of(
        new WithholdingTaxCalculator.Bracket(bd("0"), bd("0"), bd("0")),
        new WithholdingTaxCalculator.Bracket(bd("250000"), bd("0"), bd("0.15")),
        new WithholdingTaxCalculator.Bracket(bd("400000"), bd("22500"), bd("0.20")),
        new WithholdingTaxCalculator.Bracket(bd("800000"), bd("102500"), bd("0.25")),
        new WithholdingTaxCalculator.Bracket(bd("2000000"), bd("402500"), bd("0.30")),
        new WithholdingTaxCalculator.Bracket(bd("8000000"), bd("2202500"), bd("0.35"))
    );
    @Test void eachThresholdMatchesStatutorySchedule() {
        assertEquals(bd("0.00"), WithholdingTaxCalculator.tax(BRACKETS, bd("250000")));
        assertEquals(bd("22500.00"), WithholdingTaxCalculator.tax(BRACKETS, bd("400000")));
        assertEquals(bd("102500.00"), WithholdingTaxCalculator.tax(BRACKETS, bd("800000")));
        assertEquals(bd("402500.00"), WithholdingTaxCalculator.tax(BRACKETS, bd("2000000")));
        assertEquals(bd("2202500.00"), WithholdingTaxCalculator.tax(BRACKETS, bd("8000000")));
        assertEquals(bd("2552500.00"), WithholdingTaxCalculator.tax(BRACKETS, bd("9000000")));
    }
}
