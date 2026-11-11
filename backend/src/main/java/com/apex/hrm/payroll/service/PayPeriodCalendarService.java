package com.apex.hrm.payroll.service;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.payroll.domain.PayGroup;
import com.apex.hrm.payroll.domain.PayPeriod;
import com.apex.hrm.payroll.repo.PayGroupRepository;
import com.apex.hrm.payroll.repo.PayPeriodRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Generates non-overlapping calendar periods while keeping each pay date explicit and reviewable. */
@Service
public class PayPeriodCalendarService {
    public record Result(int created, int alreadyPresent) {}

    private final PayGroupRepository groups;
    private final PayPeriodRepository periods;

    public PayPeriodCalendarService(PayGroupRepository groups, PayPeriodRepository periods) {
        this.groups = groups;
        this.periods = periods;
    }

    @Transactional
    public Result generate(UUID groupId, LocalDate from, LocalDate through, int payDateOffsetDays) {
        PayGroup group = groups.findById(groupId).orElseThrow(() -> new NotFoundException("Pay group", groupId));
        if (from == null || through == null || through.isBefore(from)) {
            throw new BusinessException("Calendar end date must be on or after its start date");
        }
        if (payDateOffsetDays < 0 || payDateOffsetDays > 31) {
            throw new BusinessException("Pay date offset must be between 0 and 31 calendar days");
        }
        validateAnchor(group.getFrequency(), from);
        if ("WEEKLY".equals(group.getFrequency()) && (through.toEpochDay() - from.toEpochDay() + 1) % 7 != 0) {
            throw new BusinessException("Weekly calendar end date must complete a whole 7-day pay period");
        }
        if (("MONTHLY".equals(group.getFrequency()) && through.getDayOfMonth() != through.lengthOfMonth())
                || ("SEMI_MONTHLY".equals(group.getFrequency()) && through.getDayOfMonth() != 15
                && through.getDayOfMonth() != through.lengthOfMonth())) {
            throw new BusinessException("Monthly calendar end dates must be month-end; semi-monthly calendars must end on the 15th or month-end");
        }

        List<PayPeriod> existing = periods.findByPayGroupId(groupId);
        List<PayPeriod> planned = build(group.getFrequency(), from, through, payDateOffsetDays);
        int created = 0;
        int alreadyPresent = 0;
        for (PayPeriod candidate : planned) {
            boolean identical = existing.stream().anyMatch(row -> row.getStartDate().equals(candidate.getStartDate())
                    && row.getEndDate().equals(candidate.getEndDate()));
            if (identical) {
                alreadyPresent++;
                continue;
            }
            boolean overlap = existing.stream().anyMatch(row -> !row.getEndDate().isBefore(candidate.getStartDate())
                    && !row.getStartDate().isAfter(candidate.getEndDate()));
            if (overlap) {
                throw new BusinessException("Generated period " + candidate.getStartDate() + " to " + candidate.getEndDate()
                        + " overlaps an existing period. Correct the calendar before generating more periods.");
            }
            candidate.setPayGroupId(groupId);
            periods.save(candidate);
            existing.add(candidate);
            created++;
        }
        return new Result(created, alreadyPresent);
    }

    private static List<PayPeriod> build(String frequency, LocalDate from, LocalDate through, int payDateOffsetDays) {
        List<PayPeriod> result = new ArrayList<>();
        LocalDate start = from;
        int sequence = 1;
        while (!start.isAfter(through)) {
            LocalDate periodStart = start;
            LocalDate end;
            int periodOfMonth;
            switch (frequency) {
                case "MONTHLY" -> {
                    end = start.withDayOfMonth(start.lengthOfMonth());
                    periodOfMonth = 1;
                    start = start.plusMonths(1).withDayOfMonth(1);
                }
                case "SEMI_MONTHLY" -> {
                    if (start.getDayOfMonth() == 1) {
                        end = start.withDayOfMonth(15);
                        periodOfMonth = 1;
                        start = start.withDayOfMonth(16);
                    } else {
                        end = start.withDayOfMonth(start.lengthOfMonth());
                        periodOfMonth = 2;
                        start = start.plusMonths(1).withDayOfMonth(1);
                    }
                }
                case "WEEKLY" -> {
                    end = start.plusDays(6);
                    periodOfMonth = sequence++;
                    start = end.plusDays(1);
                }
                case "DAILY" -> {
                    end = start;
                    periodOfMonth = sequence++;
                    start = start.plusDays(1);
                }
                default -> throw new BusinessException("Unsupported pay frequency " + frequency);
            }
            if (end.isAfter(through)) end = through;
            PayPeriod period = new PayPeriod();
            period.setPeriodOfMonth(periodOfMonth);
            period.setStartDate(periodStart);
            period.setEndDate(end);
            period.setPayDate(end.plusDays(payDateOffsetDays));
            result.add(period);
        }
        return result;
    }

    private static void validateAnchor(String frequency, LocalDate from) {
        if ("MONTHLY".equals(frequency) && from.getDayOfMonth() != 1) {
            throw new BusinessException("Monthly calendars must start on the first day of a month");
        }
        if ("SEMI_MONTHLY".equals(frequency) && from.getDayOfMonth() != 1 && from.getDayOfMonth() != 16) {
            throw new BusinessException("Semi-monthly calendars must start on the 1st or 16th of a month");
        }
    }
}
