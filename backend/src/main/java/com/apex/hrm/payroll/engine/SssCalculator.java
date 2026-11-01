package com.apex.hrm.payroll.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Monthly SSS contribution: MSC is the compensation rounded to the nearest step, clamped to [min, max]. */
public final class SssCalculator {
    private SssCalculator() {
    }

    public record Rule(BigDecimal employeeRate, BigDecimal employerRate, BigDecimal minBase, BigDecimal maxBase,
                       BigDecimal step, BigDecimal ecLow, BigDecimal ecHigh, BigDecimal ecThreshold) {
    }

    public record Result(BigDecimal msc, BigDecimal employee, BigDecimal employer, BigDecimal ec) {
    }

    public static Result monthly(Rule r, BigDecimal monthlyCompensation) {
        BigDecimal msc = monthlyCompensation.divide(r.step(), 0, RoundingMode.HALF_UP).multiply(r.step());
        msc = msc.max(r.minBase()).min(r.maxBase());
        BigDecimal ec = msc.compareTo(r.ecThreshold()) < 0 ? r.ecLow() : r.ecHigh();
        return new Result(msc, Money.round(msc.multiply(r.employeeRate())),
                Money.round(msc.multiply(r.employerRate())), Money.round(ec));
    }
}
