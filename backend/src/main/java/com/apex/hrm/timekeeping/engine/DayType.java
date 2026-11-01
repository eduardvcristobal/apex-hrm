package com.apex.hrm.timekeeping.engine;

public enum DayType {
    ORDINARY, REST_DAY, SPECIAL_HOLIDAY, SPECIAL_HOLIDAY_REST_DAY, REGULAR_HOLIDAY, REGULAR_HOLIDAY_REST_DAY;

    /** holidayType: REGULAR, SPECIAL_NON_WORKING, SPECIAL_WORKING or null. Special working days pay as ordinary days. */
    public static DayType resolve(boolean restDay, String holidayType) {
        if ("REGULAR".equals(holidayType)) {
            return restDay ? REGULAR_HOLIDAY_REST_DAY : REGULAR_HOLIDAY;
        }
        if ("SPECIAL_NON_WORKING".equals(holidayType)) {
            return restDay ? SPECIAL_HOLIDAY_REST_DAY : SPECIAL_HOLIDAY;
        }
        return restDay ? REST_DAY : ORDINARY;
    }
}
