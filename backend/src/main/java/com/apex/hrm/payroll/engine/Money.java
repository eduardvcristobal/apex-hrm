package com.apex.hrm.payroll.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
    public static final BigDecimal ZERO = new BigDecimal("0.00");

    private Money() {
    }

    public static BigDecimal round(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
