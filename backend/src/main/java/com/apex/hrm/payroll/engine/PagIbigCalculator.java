package com.apex.hrm.payroll.engine;

import java.math.BigDecimal;

/** Pag-IBIG (HDMF) contribution on monthly compensation capped at the maximum fund salary. */
public final class PagIbigCalculator {
    private PagIbigCalculator() {
    }

    public record Rule(BigDecimal employeeRate, BigDecimal employerRate, BigDecimal maxBase) {
    }

    public record Result(BigDecimal base, BigDecimal employee, BigDecimal employer) {
    }

    public static Result monthly(Rule r, BigDecimal monthlyCompensation) {
        BigDecimal base = monthlyCompensation.min(r.maxBase());
        return new Result(base, Money.round(base.multiply(r.employeeRate())), Money.round(base.multiply(r.employerRate())));
    }
}
