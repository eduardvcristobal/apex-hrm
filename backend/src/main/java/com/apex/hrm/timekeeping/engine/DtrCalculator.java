package com.apex.hrm.timekeeping.engine;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Turns the first IN / last OUT of a day into payable minutes. Pure function: no I/O, safe to re-run.
 * Rules: late counts once the grace period is exceeded; the unpaid break is deducted when the worked span is
 * at least 5 hours; overtime is time worked after the scheduled end (payable only up to the approved minutes).
 */
public final class DtrCalculator {
    private static final int BREAK_DEDUCTION_THRESHOLD_MINUTES = 300;
    private static final LocalTime NIGHT_START = LocalTime.of(22, 0);

    private DtrCalculator() {
    }

    public record Schedule(LocalTime start, LocalTime end, int breakMinutes, int graceMinutes) {
    }

    public record Result(int regularMinutes, int lateMinutes, int undertimeMinutes, int nightMinutes,
                         int nightOtMinutes, int otDetectedMinutes) {
        static final Result EMPTY = new Result(0, 0, 0, 0, 0, 0);
    }

    public static Result compute(LocalDate workDate, Schedule s, Instant firstIn, Instant lastOut, ZoneId zone) {
        if (firstIn == null || lastOut == null || !lastOut.isAfter(firstIn)) {
            return Result.EMPTY;
        }
        Instant schedStart = workDate.atTime(s.start()).atZone(zone).toInstant();
        Instant schedEnd = workDate.atTime(s.end()).atZone(zone).toInstant();
        if (!schedEnd.isAfter(schedStart)) { // shift crosses midnight
            schedEnd = workDate.plusDays(1).atTime(s.end()).atZone(zone).toInstant();
        }

        int late = firstIn.isAfter(schedStart.plus(Duration.ofMinutes(s.graceMinutes())))
                ? minutes(schedStart, firstIn) : 0;
        int undertime = lastOut.isBefore(schedEnd) ? minutes(lastOut, schedEnd) : 0;

        Instant ovStart = max(firstIn, schedStart);
        Instant ovEnd = min(lastOut, schedEnd);
        int overlap = Math.max(0, minutes(ovStart, ovEnd));
        int regular = overlap >= BREAK_DEDUCTION_THRESHOLD_MINUTES ? overlap - s.breakMinutes() : overlap;
        regular = Math.max(0, regular);

        int otDetected = lastOut.isAfter(schedEnd) ? minutes(max(firstIn, schedEnd), lastOut) : 0;
        int night = Math.min(nightOverlap(workDate, ovStart, ovEnd, zone), regular);
        int nightOt = otDetected > 0 ? nightOverlap(workDate, max(firstIn, schedEnd), lastOut, zone) : 0;
        return new Result(regular, late, undertime, night, nightOt, otDetected);
    }

    /** Minutes of [from, to] that fall between 22:00 and 06:00, for the night starting the day before or on workDate. */
    static int nightOverlap(LocalDate workDate, Instant from, Instant to, ZoneId zone) {
        if (!to.isAfter(from)) {
            return 0;
        }
        int total = 0;
        for (int k = -1; k <= 0; k++) {
            Instant ns = workDate.plusDays(k).atTime(NIGHT_START).atZone(zone).toInstant();
            Instant ne = ns.plus(Duration.ofHours(8));
            Instant a = max(from, ns);
            Instant b = min(to, ne);
            if (b.isAfter(a)) {
                total += minutes(a, b);
            }
        }
        return total;
    }

    private static int minutes(Instant a, Instant b) {
        return (int) Duration.between(a, b).toMinutes();
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
