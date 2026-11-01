package com.apex.hrm.payroll.engine;

import java.math.BigDecimal;

/** Pag-IBIG (HDMF) contribution on monthly compensation capped at the maximum fund salary. */
public final class PagIbigCalculator {
    private PagIbigCalculator() {
    }

    /** Members earning the threshold or less pay a lower employee rate; the employer rate stays the same. */
    public record Rule(BigDecimal employeeRate, BigDecimal employerRate, BigDecimal maxBase,
                       BigDecimal lowIncomeThreshold, BigDecimal lowIncomeEmployeeRate) {
        public Rule(BigDecimal employeeRate, BigDecimal employerRate, BigDecimal maxBase) {
            this(employeeRate, employerRate, maxBase, new BigDecimal("1500"), new BigDecimal("0.01"));
        }
    }

    public record Result(BigDecimal base, BigDecimal employee, BigDecimal employer) {
    }

    public static Result monthly(Rule r, BigDecimal monthlyCompensation) {
        BigDecimal base = monthlyCompensation.min(r.maxBase());
        BigDecimal employeeRate = monthlyCompensation.compareTo(r.lowIncomeThreshold()) <= 0 ? r.lowIncomeEmployeeRate() : r.employeeRate();
        return new Result(base, Money.round(base.multiply(employeeRate)), Money.round(base.multiply(r.employerRate())));
    }
}
