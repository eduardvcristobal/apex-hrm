package com.apex.hrm.payroll.engine;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnnualCompensationLedgerTest {
    private static AnnualCompensationLedger.Item item(String code, String kind, String amount, boolean taxable) {
        return new AnnualCompensationLedger.Item(code, kind, new BigDecimal(amount), taxable);
    }
    @Test void reconcilesLockedPayrollComponents() {
        var totals = AnnualCompensationLedger.aggregate(List.of(
            item("BASIC", "EARNING", "26000", true),
            item("OT", "EARNING", "1000", true),
            item("ABSENCE", "DEDUCTION", "1000", true),
            item("PRORATION", "DEDUCTION", "500", true),
            item("SSS_EE", "DEDUCTION", "1500", false),
            item("PHIC_EE", "DEDUCTION", "650", false),
            item("HDMF_EE", "DEDUCTION", "200", false),
            item("LOAN", "DEDUCTION", "500", false),
            item("THIRTEENTH_MONTH", "EARNING", "3000", false),
            item("OTHER_BENEFIT", "EARNING", "4000", false),
            item("WTAX", "DEDUCTION", "1500", false)
        ));
        assertEquals(new BigDecimal("23150.00"), totals.regularTaxable());
        assertEquals(new BigDecimal("3000.00"), totals.thirteenthMonth());
        assertEquals(new BigDecimal("4000.00"), totals.otherBenefits());
        assertEquals(new BigDecimal("1500.00"), totals.withheld());
    }
    @Test void negativeAmountRejected() {
        assertThrows(IllegalArgumentException.class, () -> AnnualCompensationLedger.aggregate(
            List.of(item("BASIC", "EARNING", "-1", true))));
    }
}
