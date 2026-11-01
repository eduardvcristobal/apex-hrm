package com.apex.hrm.timekeeping.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.apex.hrm.personnel.domain.Employee;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import com.apex.hrm.personnel.repo.EmploymentRecordRepository;
import com.apex.hrm.timekeeping.domain.*;
import com.apex.hrm.timekeeping.repo.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M3.2.2: synthetic Philippine shift punches flow through the real DTR service and calculator. */
class AttendanceDtrCertificationTest {
    private final EmployeeScheduleRepository assignments = mock(EmployeeScheduleRepository.class);
    private final WorkScheduleRepository schedules = mock(WorkScheduleRepository.class);
    private final HolidayRepository holidays = mock(HolidayRepository.class);
    private final TimeLogRepository logs = mock(TimeLogRepository.class);
    private final DtrDayRepository dtrDays = mock(DtrDayRepository.class);
    private final OvertimeRequestRepository overtime = mock(OvertimeRequestRepository.class);
    private final LeaveRequestRepository leave = mock(LeaveRequestRepository.class);
    private final LeaveTypeRepository leaveTypes = mock(LeaveTypeRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final EmploymentRecordRepository employment = mock(EmploymentRecordRepository.class);
    private final UUID employeeId = UUID.randomUUID();
    private final UUID scheduleId = UUID.randomUUID();
    private final LocalDate day = LocalDate.of(2026, 10, 5); // Monday

    @Test
    void timedPunchesComputeLateMinutesAndDetectedOvertime() {
        Employee employee = employee();
        WorkSchedule schedule = schedule();
        EmployeeSchedule assigned = assigned();
        when(assignments.findOverlapping(day, day)).thenReturn(List.of(assigned));
        when(employees.findById(employeeId)).thenReturn(Optional.of(employee));
        when(schedules.findById(scheduleId)).thenReturn(Optional.of(schedule));
        when(employment.findFirstByEmployeeIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(employeeId, day))
                .thenReturn(Optional.empty());
        when(holidays.findByHolidayDate(day)).thenReturn(List.of());
        when(dtrDays.findByEmployeeIdAndWorkDate(employeeId, day)).thenReturn(Optional.empty());
        when(logs.findByEmployeeIdAndLogTimeBetweenAndStatusOrderByLogTime(eq(employeeId), any(), any(), eq("VALID")))
                .thenReturn(List.of(punch("IN", "2026-10-05T01:30:00Z"),
                                    punch("OUT", "2026-10-05T11:00:00Z")));
        when(overtime.findByEmployeeIdAndWorkDateAndStatus(employeeId, day, "APPROVED")).thenReturn(List.of());
        when(leave.findByEmployeeIdAndStatusAndDateFromLessThanEqualAndDateToGreaterThanEqual(
                employeeId, "APPROVED", day, day)).thenReturn(List.of());
        DtrDay[] saved = new DtrDay[1];
        when(dtrDays.save(any(DtrDay.class))).thenAnswer(inv -> {
            saved[0] = inv.getArgument(0);
            return saved[0];
        });
        int processed = service().process(day, day, employeeId);
        assertEquals(1, processed);
        assertNotNull(saved[0]);
        assertEquals("ORDINARY", saved[0].getDayType());
        assertEquals(450, saved[0].getRegularMinutes());
        assertEquals(30, saved[0].getLateMinutes());
        assertEquals(0, saved[0].getUndertimeMinutes());
        assertEquals(60, saved[0].getOtDetectedMinutes());
        assertEquals(0, saved[0].getOtApprovedMinutes()); // detected OT is not automatically payable
        assertFalse(saved[0].getAbsent());
    }

    @Test
    void lockedDtrCannotBeOverwrittenByReprocessing() {
        when(assignments.findOverlapping(day, day)).thenReturn(List.of(assigned()));
        when(employees.findById(employeeId)).thenReturn(Optional.of(employee()));
        when(schedules.findById(scheduleId)).thenReturn(Optional.of(schedule()));
        when(employment.findFirstByEmployeeIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(employeeId, day))
                .thenReturn(Optional.empty());
        DtrDay locked = new DtrDay();
        locked.setStatus("LOCKED");
        locked.setRegularMinutes(480);
        when(dtrDays.findByEmployeeIdAndWorkDate(employeeId, day)).thenReturn(Optional.of(locked));
        assertEquals(0, service().process(day, day, employeeId));
        assertEquals(480, locked.getRegularMinutes());
        verify(dtrDays, never()).save(any());
        verifyNoInteractions(logs);
    }

    private DtrService service() {
        return new DtrService(assignments, schedules, holidays, logs, dtrDays, overtime,
                leave, leaveTypes, employees, employment, "Asia/Manila");
    }

    private Employee employee() {
        Employee e = new Employee();
        e.setId(employeeId);
        e.setHireDate(LocalDate.of(2025, 1, 1));
        return e;
    }

    private EmployeeSchedule assigned() {
        EmployeeSchedule a = new EmployeeSchedule();
        a.setEmployeeId(employeeId);
        a.setScheduleId(scheduleId);
        a.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        return a;
    }

    private WorkSchedule schedule() {
        WorkSchedule s = new WorkSchedule();
        s.setId(scheduleId);
        s.setStartTime(LocalTime.of(9, 0));
        s.setEndTime(LocalTime.of(18, 0));
        s.setBreakMinutes(60);
        s.setGraceMinutes(5);
        s.setRestDays("SAT,SUN");
        return s;
    }

    private TimeLog punch(String type, String timestamp) {
        TimeLog log = new TimeLog();
        log.setEmployeeId(employeeId);
        log.setLogType(type);
        log.setLogTime(Instant.parse(timestamp));
        return log;
    }
}
