package com.apex.hrm.payroll.engine;

import java.math.BigDecimal;

/** Pag-IBIG (HDMF) contribution on monthly compensation capped at the maximum fund salary. */
public final class PagIbigCalculator {
    private PagIbigCalculator() {
    }

    /** Members at or below the configured low-income threshold use its employee rate. */
    public record Rule(BigDecimal employeeRate, BigDecimal employerRate, BigDecimal maxBase,
                       BigDecimal lowIncomeThreshold, BigDecimal lowIncomeEmployeeRate) {
        public Rule(BigDecimal employeeRate, BigDecimal employerRate, BigDecimal maxBase) {
            this(employeeRate, employerRate, maxBase, null, null);
        }
    }

    public record Result(BigDecimal base, BigDecimal employee, BigDecimal employer) {
    }

    public static Result monthly(Rule r, BigDecimal monthlyCompensation) {
        BigDecimal base = monthlyCompensation.min(r.maxBase());
        BigDecimal employeeRate = r.lowIncomeThreshold() != null && r.lowIncomeEmployeeRate() != null
                && monthlyCompensation.compareTo(r.lowIncomeThreshold()) <= 0
                ? r.lowIncomeEmployeeRate() : r.employeeRate();
        return new Result(base, Money.round(base.multiply(employeeRate)), Money.round(base.multiply(r.employerRate())));
    }
}
