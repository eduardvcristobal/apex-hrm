package com.apex.hrm.payroll.engine;

import com.apex.hrm.timekeeping.engine.DayType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts processed daily time records into basic pay, premiums, overtime, night differential and attendance deductions.
 * Premium pay stacks multiplicatively: hourly x day multiplier x overtime multiplier x (1 + night differential).
 */
public final class AttendancePayCalculator {
    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);

    private AttendancePayCalculator() {
    }

    public record Config(PayBasis basis, BigDecimal baseRate, BigDecimal daysFactor, BigDecimal hoursPerDay,
                         int periodsPerMonth) {
    }

    public record DayInput(LocalDate date, DayType dayType, int regularMinutes, int lateMinutes, int undertimeMinutes,
                           int nightMinutes, int nightOtMinutes, int otPayableMinutes, boolean absent,
                           boolean paidLeave) {
    }

    public record PremiumRuleData(DayType dayType, BigDecimal workedMultiplier, BigDecimal otMultiplier,
                                  BigDecimal unworkedMultiplier, boolean baseIncludedMonthly,
                                  BigDecimal nightDiffRate) {
    }

    public static BigDecimal dailyRate(Config c) {
        return c.basis() == PayBasis.DAILY ? c.baseRate()
                : c.baseRate().multiply(BigDecimal.valueOf(12)).divide(c.daysFactor(), 6, RoundingMode.HALF_UP);
    }

    public static BigDecimal hourlyRate(Config c) {
        return dailyRate(c).divide(c.hoursPerDay(), 6, RoundingMode.HALF_UP);
    }

    public static List<PayLine> compute(Config c, Map<DayType, PremiumRuleData> rules, List<DayInput> days) {
        BigDecimal daily = dailyRate(c);
        BigDecimal hourly = hourlyRate(c);
        boolean monthly = c.basis() == PayBasis.MONTHLY;

        BigDecimal basic = monthly
                ? c.baseRate().divide(BigDecimal.valueOf(c.periodsPerMonth()), 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal premium = BigDecimal.ZERO;
        BigDecimal ot = BigDecimal.ZERO;
        BigDecimal nd = BigDecimal.ZERO;
        BigDecimal holidayPay = BigDecimal.ZERO;
        BigDecimal leavePay = BigDecimal.ZERO;
        BigDecimal absenceDays = BigDecimal.ZERO;
        BigDecimal lateUtMinutes = BigDecimal.ZERO;
        BigDecimal workedDays = BigDecimal.ZERO;

        for (DayInput d : days) {
            PremiumRuleData r = rules.getOrDefault(d.dayType(), rules.get(DayType.ORDINARY));
            BigDecimal worked = r.workedMultiplier();
            if (d.regularMinutes() > 0) {
                BigDecimal hours = hours(d.regularMinutes());
                BigDecimal base = hours.multiply(hourly);
                if (monthly) {
                    BigDecimal factor = r.baseIncludedMonthly() ? worked.subtract(BigDecimal.ONE) : worked;
                    premium = premium.add(base.multiply(factor));
                    lateUtMinutes = lateUtMinutes.add(BigDecimal.valueOf(d.lateMinutes() + d.undertimeMinutes()));
                } else {
                    basic = basic.add(base);
                    premium = premium.add(base.multiply(worked.subtract(BigDecimal.ONE)));
                    workedDays = workedDays.add(BigDecimal.ONE);
                }
                nd = nd.add(hours(d.nightMinutes()).multiply(hourly).multiply(worked).multiply(r.nightDiffRate()));
            } else if (d.absent()) {
                if (monthly) {
                    absenceDays = absenceDays.add(BigDecimal.ONE);
                }
            } else if (d.paidLeave()) {
                if (!monthly) {
                    leavePay = leavePay.add(daily);
                }
            } else if (!monthly && r.unworkedMultiplier().signum() > 0) {
                holidayPay = holidayPay.add(daily.multiply(r.unworkedMultiplier()));
            }
            if (d.otPayableMinutes() > 0) {
                BigDecimal otRate = hourly.multiply(worked).multiply(r.otMultiplier());
                ot = ot.add(hours(d.otPayableMinutes()).multiply(otRate));
                int nightOt = Math.min(d.nightOtMinutes(), d.otPayableMinutes());
                nd = nd.add(hours(nightOt).multiply(otRate).multiply(r.nightDiffRate()));
            }
        }

        List<PayLine> lines = new ArrayList<>();
        add(lines, "BASIC", "Basic pay", PayLine.EARNING, basic, true);
        add(lines, "HOLIDAY_PAY", "Holiday pay (unworked)", PayLine.EARNING, holidayPay, true);
        add(lines, "LEAVE_PAY", "Paid leave", PayLine.EARNING, leavePay, true);
        add(lines, "PREMIUM", "Holiday / rest day premium", PayLine.EARNING, premium, true);
        add(lines, "OT", "Overtime pay", PayLine.EARNING, ot, true);
        add(lines, "ND", "Night differential", PayLine.EARNING, nd, true);
        add(lines, "ABSENCE", "Absences", PayLine.DEDUCTION, absenceDays.multiply(daily), true);
        add(lines, "LATE_UT", "Late / undertime", PayLine.DEDUCTION,
                lateUtMinutes.divide(SIXTY, 6, RoundingMode.HALF_UP).multiply(hourly), true);
        return lines;
    }

    private static BigDecimal hours(int minutes) {
        return BigDecimal.valueOf(minutes).divide(SIXTY, 6, RoundingMode.HALF_UP);
    }

    private static void add(List<PayLine> out, String code, String desc, String category, BigDecimal amount,
                            boolean taxable) {
        BigDecimal rounded = Money.round(amount);
        if (rounded.signum() > 0) {
            out.add(new PayLine(code, desc, category, null, null, rounded, taxable));
        }
    }
}
