package com.apex.hrm.timekeeping.service;

import com.apex.hrm.personnel.domain.Employee;
import com.apex.hrm.personnel.domain.EmploymentRecord;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import com.apex.hrm.personnel.repo.EmploymentRecordRepository;
import com.apex.hrm.timekeeping.domain.DtrDay;
import com.apex.hrm.timekeeping.domain.EmployeeSchedule;
import com.apex.hrm.timekeeping.domain.Holiday;
import com.apex.hrm.timekeeping.domain.LeaveRequest;
import com.apex.hrm.timekeeping.domain.LeaveType;
import com.apex.hrm.timekeeping.domain.TimeLog;
import com.apex.hrm.timekeeping.domain.WorkSchedule;
import com.apex.hrm.timekeeping.engine.DayType;
import com.apex.hrm.timekeeping.engine.DtrCalculator;
import com.apex.hrm.timekeeping.repo.DtrDayRepository;
import com.apex.hrm.timekeeping.repo.EmployeeScheduleRepository;
import com.apex.hrm.timekeeping.repo.HolidayRepository;
import com.apex.hrm.timekeeping.repo.LeaveRequestRepository;
import com.apex.hrm.timekeeping.repo.LeaveTypeRepository;
import com.apex.hrm.timekeeping.repo.OvertimeRequestRepository;
import com.apex.hrm.timekeeping.repo.TimeLogRepository;
import com.apex.hrm.timekeeping.repo.WorkScheduleRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import com.apex.hrm.payroll.engine.HolidayPayRule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds daily time records (dtr_day) from raw punches, schedules, holidays, approved leave and approved overtime. */
@Service
public class DtrService {
    private final EmployeeScheduleRepository assignments;
    private final WorkScheduleRepository schedules;
    private final HolidayRepository holidays;
    private final TimeLogRepository timeLogs;
    private final DtrDayRepository dtrDays;
    private final OvertimeRequestRepository overtime;
    private final LeaveRequestRepository leaveRequests;
    private final LeaveTypeRepository leaveTypes;
    private final EmployeeRepository employees;
    private final EmploymentRecordRepository employment;
    private final ZoneId zone;

    public DtrService(EmployeeScheduleRepository assignments, WorkScheduleRepository schedules, HolidayRepository holidays,
                      TimeLogRepository timeLogs, DtrDayRepository dtrDays, OvertimeRequestRepository overtime,
                      LeaveRequestRepository leaveRequests, LeaveTypeRepository leaveTypes, EmployeeRepository employees,
                      EmploymentRecordRepository employment, @Value("${hrm.timezone:Asia/Manila}") String timezone) {
        this.assignments = assignments;
        this.schedules = schedules;
        this.holidays = holidays;
        this.timeLogs = timeLogs;
        this.dtrDays = dtrDays;
        this.overtime = overtime;
        this.leaveRequests = leaveRequests;
        this.leaveTypes = leaveTypes;
        this.employees = employees;
        this.employment = employment;
        this.zone = ZoneId.of(timezone);
    }

    /** Recomputes DTR for [from, to]. Locked days are skipped. employeeId may be null for everyone with a schedule. */
    @Transactional
    public int process(LocalDate from, LocalDate to, UUID employeeId) {
        Map<UUID, List<EmployeeSchedule>> byEmployee = assignments.findOverlapping(from, to).stream()
                .filter(a -> employeeId == null || a.getEmployeeId().equals(employeeId))
                .collect(Collectors.groupingBy(EmployeeSchedule::getEmployeeId));
        Map<UUID, WorkSchedule> scheduleCache = new HashMap<>();
        Map<LocalDate, List<Holiday>> holidayCache = new HashMap<>();
        Map<UUID, LeaveType> leaveTypeCache = new HashMap<>();
        int processed = 0;

        for (Map.Entry<UUID, List<EmployeeSchedule>> entry : byEmployee.entrySet()) {
            UUID empId = entry.getKey();
            Employee emp = employees.findById(empId).orElse(null);
            if (emp == null) {
                continue;
            }
            UUID locationId = employment
                    .findFirstByEmployeeIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(empId, to)
                    .map(EmploymentRecord::getWorkLocationId).orElse(null);

            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                if (d.isBefore(emp.getHireDate()) || (emp.getSeparationDate() != null && d.isAfter(emp.getSeparationDate()))) {
                    continue;
                }
                EmployeeSchedule assignment = pick(entry.getValue(), d);
                if (assignment == null) {
                    continue;
                }
                WorkSchedule ws = scheduleCache.computeIfAbsent(assignment.getScheduleId(),
                        id -> schedules.findById(id).orElseThrow());
                DtrDay dtr = dtrDays.findByEmployeeIdAndWorkDate(empId, d).orElseGet(DtrDay::new);
                if ("LOCKED".equals(dtr.getStatus())) {
                    continue;
                }

                boolean rest = restDays(ws).contains(d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase());
                DayType dayType = DayType.resolve(rest, holidayType(d, locationId, holidayCache));

                Instant schedStart = d.atTime(ws.getStartTime()).atZone(zone).toInstant();
                Instant schedEnd = d.atTime(ws.getEndTime()).atZone(zone).toInstant();
                if (!schedEnd.isAfter(schedStart)) {
                    schedEnd = d.plusDays(1).atTime(ws.getEndTime()).atZone(zone).toInstant();
                }
                List<TimeLog> punches = timeLogs.findByEmployeeIdAndLogTimeBetweenAndStatusOrderByLogTime(
                        empId, schedStart.minus(Duration.ofHours(4)), schedEnd.plus(Duration.ofHours(6)), "VALID");
                Instant firstIn = punches.stream().filter(l -> "IN".equals(l.getLogType()))
                        .map(TimeLog::getLogTime).findFirst().orElse(null);
                Instant lastOut = punches.stream().filter(l -> "OUT".equals(l.getLogType()))
                        .map(TimeLog::getLogTime).reduce((a, b) -> b).orElse(null);

                DtrCalculator.Result r = DtrCalculator.compute(d,
                        new DtrCalculator.Schedule(ws.getStartTime(), ws.getEndTime(), ws.getBreakMinutes(), ws.getGraceMinutes()),
                        firstIn, lastOut, zone);

                int otApproved = overtime.findByEmployeeIdAndWorkDateAndStatus(empId, d, "APPROVED").stream()
                        .mapToInt(o -> o.getApprovedMinutes() == null ? 0 : o.getApprovedMinutes()).sum();
                boolean paidLeave = false;
                for (LeaveRequest lr : leaveRequests.findByEmployeeIdAndStatusAndDateFromLessThanEqualAndDateToGreaterThanEqual(
                        empId, "APPROVED", d, d)) {
                    LeaveType lt = leaveTypeCache.computeIfAbsent(lr.getLeaveTypeId(), id -> leaveTypes.findById(id).orElseThrow());
                    paidLeave = paidLeave || Boolean.TRUE.equals(lt.getPaid());
                }
                boolean worked = r.regularMinutes() > 0;

                dtr.setEmployeeId(empId);
                dtr.setWorkDate(d);
                dtr.setScheduleId(ws.getId());
                dtr.setDayType(dayType.name());
                dtr.setFirstIn(firstIn);
                dtr.setLastOut(lastOut);
                dtr.setRegularMinutes(r.regularMinutes());
                dtr.setLateMinutes(r.lateMinutes());
                dtr.setUndertimeMinutes(r.undertimeMinutes());
                dtr.setNightMinutes(r.nightMinutes());
                dtr.setNightOtMinutes(r.nightOtMinutes());
                dtr.setOtDetectedMinutes(r.otDetectedMinutes());
                dtr.setOtApprovedMinutes(otApproved);
                dtr.setPaidLeave(paidLeave);
                dtr.setAbsent(!worked && dayType == DayType.ORDINARY && !paidLeave);
                dtr.setHolidayPayEligible(holidayPayEligible(empId, d, dayType, worked, paidLeave));
                dtrDays.save(dtr);
                processed++;
            }
        }
        return processed;
    }

    /** Daily-paid employees get regular holiday pay only if they worked or were on paid leave the working day before. */
    private boolean holidayPayEligible(UUID empId, LocalDate d, DayType type, boolean worked, boolean paidLeave) {
        if ((type != DayType.REGULAR_HOLIDAY && type != DayType.REGULAR_HOLIDAY_REST_DAY) || worked || paidLeave) {
            return true;
        }
        Map<LocalDate, DtrDay> byDate = new HashMap<>();
        for (DtrDay x : dtrDays.findByEmployeeIdAndWorkDateBetween(empId, d.minusDays(7), d.minusDays(1))) {
            byDate.put(x.getWorkDate(), x);
        }
        List<HolidayPayRule.PriorDay> prior = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            DtrDay x = byDate.get(d.minusDays(i));
            if (x != null) {
                prior.add(new HolidayPayRule.PriorDay(DayType.ORDINARY.name().equals(x.getDayType()),
                        x.getRegularMinutes() > 0 || Boolean.TRUE.equals(x.getPaidLeave())));
            }
        }
        return HolidayPayRule.eligible(prior);
    }

    private static EmployeeSchedule pick(List<EmployeeSchedule> list, LocalDate d) {
        EmployeeSchedule best = null;
        for (EmployeeSchedule a : list) {
            boolean active = !a.getEffectiveFrom().isAfter(d) && (a.getEffectiveTo() == null || !a.getEffectiveTo().isBefore(d));
            if (active && (best == null || a.getEffectiveFrom().isAfter(best.getEffectiveFrom()))) {
                best = a;
            }
        }
        return best;
    }

    private static Set<String> restDays(WorkSchedule ws) {
        return Arrays.stream(ws.getRestDays().split(",")).map(String::trim).map(String::toUpperCase)
                .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    /** Regular beats special non-working beats special working when several holidays share a date. */
    private String holidayType(LocalDate d, UUID locationId, Map<LocalDate, List<Holiday>> cache) {
        List<Holiday> list = cache.computeIfAbsent(d, holidays::findByHolidayDate);
        String found = null;
        for (Holiday h : list) {
            if (h.getWorkLocationId() != null && !h.getWorkLocationId().equals(locationId)) {
                continue;
            }
            if ("REGULAR".equals(h.getHolidayType())) {
                return "REGULAR";
            }
            if ("SPECIAL_NON_WORKING".equals(h.getHolidayType())) {
                found = "SPECIAL_NON_WORKING";
            } else if (found == null) {
                found = h.getHolidayType();
            }
        }
        return found;
    }
}
