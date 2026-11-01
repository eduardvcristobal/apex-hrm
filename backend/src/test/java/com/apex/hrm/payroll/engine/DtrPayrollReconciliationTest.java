package com.apex.hrm.payroll.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.apex.hrm.timekeeping.engine.DayType;
import com.apex.hrm.timekeeping.engine.DtrCalculator;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** M3.2.3: real DTR math is converted to payroll earnings/deductions without fabricated inputs. */
class DtrPayrollReconciliationTest {
    private static final LocalDate WORK_DATE = LocalDate.of(2026, 10, 5);
    private static final ZoneId MANILA = ZoneId.of("Asia/Manila");

    @Test
    void lateShiftAndApprovedOvertimeReconcileToExpectedPayLines() {
        DtrCalculator.Result dtr = DtrCalculator.compute(WORK_DATE,
                new DtrCalculator.Schedule(LocalTime.of(9, 0), LocalTime.of(18, 0), 60, 5),
                Instant.parse("2026-10-05T01:30:00Z"),
                Instant.parse("2026-10-05T11:00:00Z"), MANILA);
        assertEquals(450, dtr.regularMinutes());
        assertEquals(30, dtr.lateMinutes());
        assertEquals(60, dtr.otDetectedMinutes());

        AttendancePayCalculator.Config monthly = new AttendancePayCalculator.Config(
                PayBasis.MONTHLY, new BigDecimal("26100"), new BigDecimal("261"),
                new BigDecimal("8"), 2);
        Map<DayType, AttendancePayCalculator.PremiumRuleData> rules = new EnumMap<>(DayType.class);
        rules.put(DayType.ORDINARY, new AttendancePayCalculator.PremiumRuleData(
                DayType.ORDINARY, new BigDecimal("1.00"), new BigDecimal("1.25"),
                BigDecimal.ZERO, true, new BigDecimal("0.10")));

        // DTR supplies detected OT; payroll only pays the approved portion.
        AttendancePayCalculator.DayInput unpaid = dayInput(dtr, 0);
        AttendancePayCalculator.DayInput approved = dayInput(dtr, 60);
        List<PayLine> beforeApproval = AttendancePayCalculator.compute(monthly, rules, List.of(unpaid));
        List<PayLine> afterApproval = AttendancePayCalculator.compute(monthly, rules, List.of(approved));

        assertMoney("13050.00", amount(afterApproval, "BASIC"));
        assertMoney("75.00", amount(afterApproval, "LATE_UT")); // 30 minutes at 150/hour
        assertMoney("0", amount(beforeApproval, "OT"));
        assertMoney("187.50", amount(afterApproval, "OT")); // 1 hour at 150 x 1.25
        assertMoney("13162.50", amount(afterApproval, "BASIC")
                .add(amount(afterApproval, "OT")).subtract(amount(afterApproval, "LATE_UT")));
    }

    private static AttendancePayCalculator.DayInput dayInput(DtrCalculator.Result d, int approvedOt) {
        return new AttendancePayCalculator.DayInput(WORK_DATE, DayType.ORDINARY,
                d.regularMinutes(), d.lateMinutes(), d.undertimeMinutes(),
                d.nightMinutes(), d.nightOtMinutes(), Math.min(d.otDetectedMinutes(), approvedOt),
                false, false);
    }

    private static BigDecimal amount(List<PayLine> lines, String code) {
        return lines.stream().filter(line -> code.equals(line.code()))
                .map(PayLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "unexpected amount: " + actual);
    }
}
