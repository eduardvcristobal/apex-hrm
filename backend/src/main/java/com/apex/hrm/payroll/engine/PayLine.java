package com.apex.hrm.payroll.engine;

import java.math.BigDecimal;

/** One computed payroll component. category: EARNING, DEDUCTION or EMPLOYER_COST. */
public record PayLine(String code, String description, String category, BigDecimal quantity, BigDecimal rate,
                      BigDecimal amount, boolean taxable) {
    public static final String EARNING = "EARNING";
    public static final String DEDUCTION = "DEDUCTION";
    public static final String EMPLOYER_COST = "EMPLOYER_COST";
}
