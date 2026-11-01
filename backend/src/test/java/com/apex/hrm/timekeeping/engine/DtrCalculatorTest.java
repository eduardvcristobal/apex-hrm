package com.apex.hrm.timekeeping.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.apex.hrm.timekeeping.engine.DtrCalculator.Result;
import com.apex.hrm.timekeeping.engine.DtrCalculator.Schedule;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class DtrCalculatorTest {
    private static final ZoneId MNL = ZoneId.of("Asia/Manila");
    private static final LocalDate D = LocalDate.of(2026, 11, 2);
    private static final Schedule DAY = new Schedule(LocalTime.of(9, 0), LocalTime.of(18, 0), 60, 0);

    private static Instant at(LocalDate d, int h, int m) {
        return d.atTime(h, m).atZone(MNL).toInstant();
    }

    @Test
    void fullDay() {
        Result r = DtrCalculator.compute(D, DAY, at(D, 9, 0), at(D, 18, 0), MNL);
        assertEquals(480, r.regularMinutes());
        assertEquals(0, r.lateMinutes());
        assertEquals(0, r.otDetectedMinutes());
    }

    @Test
    void lateArrival() {
        Result r = DtrCalculator.compute(D, DAY, at(D, 9, 30), at(D, 18, 0), MNL);
        assertEquals(30, r.lateMinutes());
        assertEquals(450, r.regularMinutes());
    }

    @Test
    void graceAbsorbsShortLateness() {
        Schedule grace = new Schedule(LocalTime.of(9, 0), LocalTime.of(18, 0), 60, 10);
        assertEquals(0, DtrCalculator.compute(D, grace, at(D, 9, 8), at(D, 18, 0), MNL).lateMinutes());
    }

    @Test
    void overtimeAndUndertime() {
        Result ot = DtrCalculator.compute(D, DAY, at(D, 9, 0), at(D, 20, 0), MNL);
        assertEquals(480, ot.regularMinutes());
        assertEquals(120, ot.otDetectedMinutes());
        Result ut = DtrCalculator.compute(D, DAY, at(D, 9, 0), at(D, 17, 0), MNL);
        assertEquals(60, ut.undertimeMinutes());
        assertEquals(420, ut.regularMinutes());
    }

    @Test
    void nightShiftCrossingMidnight() {
        Schedule night = new Schedule(LocalTime.of(22, 0), LocalTime.of(6, 0), 60, 0);
        Result r = DtrCalculator.compute(D, night, at(D, 22, 0), at(D.plusDays(1), 6, 0), MNL);
        assertEquals(420, r.regularMinutes());
        assertEquals(420, r.nightMinutes());
        assertEquals(0, r.lateMinutes());
    }

    @Test
    void missingPunchYieldsNothing() {
        Result r = DtrCalculator.compute(D, DAY, at(D, 9, 0), null, MNL);
        assertEquals(0, r.regularMinutes());
    }
}
