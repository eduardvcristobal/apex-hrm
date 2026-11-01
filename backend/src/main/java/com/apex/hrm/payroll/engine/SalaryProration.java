package com.apex.hrm.payroll.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Prorates a monthly salary over a pay period by calendar days. Handles hires and separations in the middle of a
 * period and salary changes in the middle of a period: each segment is the span an employee was employed at one rate.
 */
public final class SalaryProration {
    private SalaryProration() {
    }

    /** Inclusive dates. Use null for an open start or end. */
    public record Segment(BigDecimal monthlyRate, LocalDate from, LocalDate to) {
    }

    public static BigDecimal periodBasic(List<Segment> segments, LocalDate periodStart, LocalDate periodEnd, int periodsPerMonth) {
        BigDecimal periodDays = BigDecimal.valueOf(ChronoUnit.DAYS.between(periodStart, periodEnd) + 1);
        BigDecimal total = BigDecimal.ZERO;
        for (Segment s : segments) {
            LocalDate from = s.from() == null || s.from().isBefore(periodStart) ? periodStart : s.from();
            LocalDate to = s.to() == null || s.to().isAfter(periodEnd) ? periodEnd : s.to();
            if (to.isBefore(from)) {
                continue;
            }
            BigDecimal days = BigDecimal.valueOf(ChronoUnit.DAYS.between(from, to) + 1);
            BigDecimal share = s.monthlyRate().divide(BigDecimal.valueOf(periodsPerMonth), 10, RoundingMode.HALF_UP)
                    .multiply(days).divide(periodDays, 10, RoundingMode.HALF_UP);
            total = total.add(share);
        }
        return total;
    }
}
