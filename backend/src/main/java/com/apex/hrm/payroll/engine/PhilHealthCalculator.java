package com.apex.hrm.payroll.engine;

import java.math.BigDecimal;

/** Premium on basic monthly salary, bounded by a floor and ceiling and shared by employee and employer. */
public final class PhilHealthCalculator {
    private PhilHealthCalculator() {
    }

    public record Rule(BigDecimal employeeRate, BigDecimal employerRate, BigDecimal minBase, BigDecimal maxBase) {
    }

    public record Result(BigDecimal base, BigDecimal employee, BigDecimal employer) {
    }

    public static Result monthly(Rule r, BigDecimal monthlyBasic) {
        BigDecimal base = monthlyBasic.max(r.minBase()).min(r.maxBase());
        return new Result(base, Money.round(base.multiply(r.employeeRate())), Money.round(base.multiply(r.employerRate())));
    }
}
