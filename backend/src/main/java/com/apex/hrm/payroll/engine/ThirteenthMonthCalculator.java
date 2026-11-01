package com.apex.hrm.payroll.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 13th month pay = basic salary earned in the year / 12. Exempt from tax up to a ceiling shared with other benefits. */
public final class ThirteenthMonthCalculator {
    public static final BigDecimal EXEMPT_CEILING = new BigDecimal("90000");

    private ThirteenthMonthCalculator() {
    }

    public record Result(BigDecimal amount, BigDecimal exempt, BigDecimal taxable) {
    }

    /** otherBenefitsUsed: bonuses and other benefits already counted against the ceiling in the same year. */
    public static Result split(BigDecimal basicEarnedYtd, BigDecimal alreadyPaid, BigDecimal otherBenefitsUsed) {
        return split(basicEarnedYtd, alreadyPaid, BigDecimal.ZERO, otherBenefitsUsed);
    }

    /** exemptAlreadyPaid: the part of previously paid 13th-month compensation that used the shared ceiling. */
    public static Result split(BigDecimal basicEarnedYtd, BigDecimal alreadyPaid, BigDecimal exemptAlreadyPaid,
                               BigDecimal otherBenefitsUsed) {
        BigDecimal amount = Money.round(basicEarnedYtd.divide(BigDecimal.valueOf(12), 6, RoundingMode.HALF_UP).subtract(alreadyPaid)).max(Money.ZERO);
        BigDecimal room = EXEMPT_CEILING.subtract(otherBenefitsUsed).subtract(exemptAlreadyPaid).max(BigDecimal.ZERO);
        BigDecimal exempt = amount.min(room);
        return new Result(amount, Money.round(exempt), Money.round(amount.subtract(exempt)));
    }
}
