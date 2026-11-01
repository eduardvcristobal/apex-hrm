package com.apex.hrm.payroll.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CutoffProrationCalculatorTest {
    private static LocalDate day(int n) { return LocalDate.of(2026, 11, n); }
    private static BigDecimal deduction(int from, LocalDate through) {
        return CutoffProrationCalculator.deduction(new BigDecimal("15000.00"),
                day(1), day(15), day(from), through);
    }
    @Test void fullCutoffHasNoDeduction() {
        assertEquals(new BigDecimal("0.00"), deduction(1, null));
    }
    @Test void midPeriodHireIsProratedInclusively() {
        assertEquals(new BigDecimal("6000.00"), deduction(7, null));
    }
    @Test void separationIsProratedInclusively() {
        assertEquals(new BigDecimal("10000.00"), deduction(1, day(5)));
    }
    @Test void noActiveDaysDeductsWholeCutoff() {
        assertEquals(new BigDecimal("15000.00"), deduction(20, null));
    }
    @Test void fullPeriodBoundsAreClamped() {
        assertEquals(new BigDecimal("0.00"), CutoffProrationCalculator.deduction(
                new BigDecimal("15000.00"), day(1), day(15), LocalDate.of(2026,10,1), day(30)));
    }
    @Test void invalidCutoffIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CutoffProrationCalculator.deduction(
                new BigDecimal("1"), day(15), day(1), day(1), null));
    }
}
