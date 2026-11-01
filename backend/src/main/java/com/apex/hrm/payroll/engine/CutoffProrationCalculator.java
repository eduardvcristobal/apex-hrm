package com.apex.hrm.payroll.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Calendar-day proration for a monthly cutoff. Calendar-day proration is a configurable
 * business assumption, not a statutory default for every employment contract.
 * The result is the portion of full cutoff basic pay that must be withheld.
 */
public final class CutoffProrationCalculator {
    private CutoffProrationCalculator() {}

    public static BigDecimal deduction(BigDecimal fullCutoffBasic, LocalDate periodStart,
            LocalDate periodEnd, LocalDate activeFrom, LocalDate activeThrough) {
        Objects.requireNonNull(fullCutoffBasic, "fullCutoffBasic");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        Objects.requireNonNull(activeFrom, "activeFrom");
        if (periodEnd.isBefore(periodStart)) throw new IllegalArgumentException("Invalid payroll cutoff");
        if (fullCutoffBasic.signum() < 0) throw new IllegalArgumentException("Negative basic pay");
        LocalDate first = activeFrom.isAfter(periodStart) ? activeFrom : periodStart;
        LocalDate last = activeThrough == null || activeThrough.isAfter(periodEnd) ? periodEnd : activeThrough;
        long covered = ChronoUnit.DAYS.between(periodStart, periodEnd) + 1;
        long eligible = last.isBefore(first) ? 0 : ChronoUnit.DAYS.between(first, last) + 1;
        BigDecimal payable = fullCutoffBasic.multiply(BigDecimal.valueOf(eligible))
                .divide(BigDecimal.valueOf(covered), 2, RoundingMode.HALF_UP);
        return Money.round(fullCutoffBasic.subtract(payable));
    }
}
