package com.apex.hrm.payroll.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.apex.hrm.payroll.engine.AttendancePayCalculator.Config;
import com.apex.hrm.payroll.engine.AttendancePayCalculator.DayInput;
import com.apex.hrm.payroll.engine.AttendancePayCalculator.PremiumRuleData;
import com.apex.hrm.timekeeping.engine.DayType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PayrollEngineTest {
    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertTrue(bd(expected).compareTo(actual) == 0, "expected " + expected + " but was " + actual);
    }

    private static Map<DayType, PremiumRuleData> rules() {
        Map<DayType, PremiumRuleData> m = new EnumMap<>(DayType.class);
        m.put(DayType.ORDINARY, new PremiumRuleData(DayType.ORDINARY, bd("1.00"), bd("1.25"), bd("0"), true, bd("0.10")));
        m.put(DayType.REST_DAY, new PremiumRuleData(DayType.REST_DAY, bd("1.30"), bd("1.30"), bd("0"), false, bd("0.10")));
        m.put(DayType.REGULAR_HOLIDAY, new PremiumRuleData(DayType.REGULAR_HOLIDAY, bd("2.00"), bd("1.30"), bd("1.00"), true, bd("0.10")));
        return m;
    }

    private static BigDecimal amountOf(List<PayLine> lines, String code) {
        return lines.stream().filter(l -> l.code().equals(code)).map(PayLine::amount).findFirst().orElse(BigDecimal.ZERO);
    }

    private static DayInput day(DayType t, int regular, int late, int ut, int night, int nightOt, int ot, boolean absent, boolean leave) {
        return new DayInput(LocalDate.of(2026, 11, 2), t, regular, late, ut, night, nightOt, ot, absent, leave);
    }

    private static final SssCalculator.Rule SSS = new SssCalculator.Rule(bd("0.05"), bd("0.10"), bd("5000"), bd("35000"),
            bd("500"), bd("10"), bd("30"), bd("15000"));

    @Test
    void sssAtCeilingAndFloor() {
        SssCalculator.Result max = SssCalculator.monthly(SSS, bd("50000"));
        assertAmount("35000", max.msc());
        assertAmount("1750.00", max.employee());
        assertAmount("3500.00", max.employer());
        assertAmount("30.00", max.ec());
        SssCalculator.Result min = SssCalculator.monthly(SSS, bd("3000"));
        assertAmount("5000", min.msc());
        assertAmount("500.00", min.employer());
        assertAmount("10.00", min.ec());
    }

    @Test
    void sssBracketRoundsAtHalfStep() {
        assertAmount("5500", SssCalculator.monthly(SSS, bd("5250")).msc());
        assertAmount("5000", SssCalculator.monthly(SSS, bd("5249.99")).msc());
        assertAmount("35000", SssCalculator.monthly(SSS, bd("34750")).msc());
    }

    @Test
    void philhealthAndPagibig() {
        PhilHealthCalculator.Rule ph = new PhilHealthCalculator.Rule(bd("0.025"), bd("0.025"), bd("10000"), bd("100000"));
        assertAmount("750.00", PhilHealthCalculator.monthly(ph, bd("30000")).employee());
        assertAmount("250.00", PhilHealthCalculator.monthly(ph, bd("8000")).employee());
        PagIbigCalculator.Rule pi = new PagIbigCalculator.Rule(bd("0.02"), bd("0.02"), bd("10000"));
        assertAmount("200.00", PagIbigCalculator.monthly(pi, bd("50000")).employee());
    }

    @Test
    void withholdingTaxBrackets() {
        List<WithholdingTaxCalculator.Bracket> monthly = List.of(
                new WithholdingTaxCalculator.Bracket(bd("0"), bd("0"), bd("0")),
                new WithholdingTaxCalculator.Bracket(bd("20833"), bd("0"), bd("0.15")),
                new WithholdingTaxCalculator.Bracket(bd("33333"), bd("1875.00"), bd("0.20")),
                new WithholdingTaxCalculator.Bracket(bd("66667"), bd("8541.80"), bd("0.25")));
        assertAmount("0.00", WithholdingTaxCalculator.tax(monthly, bd("20000")));
        assertAmount("1075.05", WithholdingTaxCalculator.tax(monthly, bd("28000")));
        assertAmount("5208.40", WithholdingTaxCalculator.tax(monthly, bd("50000")));
    }

    @Test
    void monthlyEmployeeWithAbsenceLateOvertimeAndNight() {
        // 26,100/month, 261-day factor -> 1,200/day, 150/hour; semi-monthly basic 13,050
        Config cfg = new Config(PayBasis.MONTHLY, bd("26100"), bd("261"), bd("8"), 2);
        List<PayLine> lines = AttendancePayCalculator.compute(cfg, rules(), List.of(
                day(DayType.ORDINARY, 450, 30, 0, 60, 0, 120, false, false),
                day(DayType.ORDINARY, 0, 0, 0, 0, 0, 0, true, false)));
        assertAmount("13050.00", amountOf(lines, "BASIC"));
        assertAmount("1200.00", amountOf(lines, "ABSENCE"));
        assertAmount("75.00", amountOf(lines, "LATE_UT"));
        assertAmount("375.00", amountOf(lines, "OT"));   // 2h x 150 x 1.25
        assertAmount("15.00", amountOf(lines, "ND"));    // 1h x 150 x 10%
    }

    @Test
    void monthlyHolidayAndRestDayPremiums() {
        Config cfg = new Config(PayBasis.MONTHLY, bd("26100"), bd("261"), bd("8"), 2);
        // regular holiday worked 8h: base already paid, add the extra 100%
        assertAmount("1200.00", amountOf(AttendancePayCalculator.compute(cfg, rules(),
                List.of(day(DayType.REGULAR_HOLIDAY, 480, 0, 0, 0, 0, 0, false, false))), "PREMIUM"));
        // rest day worked 8h: not in monthly base, pay the full 130%
        assertAmount("1560.00", amountOf(AttendancePayCalculator.compute(cfg, rules(),
                List.of(day(DayType.REST_DAY, 480, 0, 0, 0, 0, 0, false, false))), "PREMIUM"));
        // overtime on a regular holiday stacks: 150 x 2.00 x 1.30 = 390/hour
        assertAmount("390.00", amountOf(AttendancePayCalculator.compute(cfg, rules(),
                List.of(day(DayType.REGULAR_HOLIDAY, 480, 0, 0, 0, 0, 60, false, false))), "OT"));
    }

    @Test
    void dailyPaidEmployee() {
        Config cfg = new Config(PayBasis.DAILY, bd("600"), bd("261"), bd("8"), 2);
        List<PayLine> worked = AttendancePayCalculator.compute(cfg, rules(),
                List.of(day(DayType.REGULAR_HOLIDAY, 480, 0, 0, 0, 0, 0, false, false)));
        assertAmount("600.00", amountOf(worked, "BASIC"));
        assertAmount("600.00", amountOf(worked, "PREMIUM"));
        List<PayLine> unworked = AttendancePayCalculator.compute(cfg, rules(),
                List.of(day(DayType.REGULAR_HOLIDAY, 0, 0, 0, 0, 0, 0, false, false)));
        assertAmount("600.00", amountOf(unworked, "HOLIDAY_PAY"));
        List<PayLine> absent = AttendancePayCalculator.compute(cfg, rules(), List.of(day(DayType.ORDINARY, 0, 0, 0, 0, 0, 0, true, false)));
        assertEquals(0, absent.size());
    }
}
