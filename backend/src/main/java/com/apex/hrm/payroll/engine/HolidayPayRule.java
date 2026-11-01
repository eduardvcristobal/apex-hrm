package com.apex.hrm.payroll.engine;

import java.util.List;

/**
 * Holiday pay eligibility for daily-paid employees on an unworked regular holiday: the employee must have worked or
 * been on paid leave on the last scheduled working day before the holiday. If that day was a rest day or another
 * holiday, the rule looks at the working day before it.
 */
public final class HolidayPayRule {
    private HolidayPayRule() {
    }

    /** scheduledWorkday: an ordinary working day (not rest day or holiday). */
    public record PriorDay(boolean scheduledWorkday, boolean workedOrPaidLeave) {
    }

    /** previousDays is ordered from the day before the holiday going backwards. With no working day found, pay it. */
    public static boolean eligible(List<PriorDay> previousDays) {
        for (PriorDay d : previousDays) {
            if (d.scheduledWorkday()) {
                return d.workedOrPaidLeave();
            }
        }
        return true;
    }
}
