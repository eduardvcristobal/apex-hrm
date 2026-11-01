package com.apex.hrm.timekeeping.web;

import com.apex.hrm.timekeeping.domain.AttendanceCorrection;
import com.apex.hrm.timekeeping.domain.LeaveLedger;
import com.apex.hrm.timekeeping.domain.LeaveRequest;
import com.apex.hrm.timekeeping.domain.OvertimeRequest;
import com.apex.hrm.timekeeping.domain.TimeLog;
import com.apex.hrm.timekeeping.service.AttendanceService;
import com.apex.hrm.timekeeping.service.DtrService;
import com.apex.hrm.timekeeping.service.LeaveService;
import com.apex.hrm.timekeeping.service.OvertimeService;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/** Workflow endpoints for attendance, DTR processing, leave and overtime. */
@RestController
@RequestMapping("/api")
public class TimekeepingController {
    public record ClockRequest(UUID employeeId, String logType, String source, BigDecimal latitude, BigDecimal longitude,
                               String deviceId, String photoRef) {
    }

    public record ManualRequest(UUID employeeId, String logType, Instant logTime, String remarks) {
    }

    public record CorrectionRequest(UUID employeeId, LocalDate workDate, Instant requestedIn, Instant requestedOut, String reason) {
    }

    public record DecisionRequest(Integer approvedMinutes) {
    }

    public record ProcessRequest(LocalDate from, LocalDate to, UUID employeeId) {
    }

    public record LeaveFileRequest(UUID employeeId, UUID leaveTypeId, LocalDate dateFrom, LocalDate dateTo, BigDecimal days,
                                   boolean halfDay, String reason) {
    }

    public record GrantRequest(UUID employeeId, UUID leaveTypeId, BigDecimal days, String remarks) {
    }

    public record OvertimeFileRequest(UUID employeeId, LocalDate workDate, int minutes, String reason) {
    }

    private final AttendanceService attendance;
    private final DtrService dtr;
    private final LeaveService leave;
    private final OvertimeService overtime;

    public TimekeepingController(AttendanceService attendance, DtrService dtr, LeaveService leave, OvertimeService overtime) {
        this.attendance = attendance;
        this.dtr = dtr;
        this.leave = leave;
        this.overtime = overtime;
    }

    @PostMapping("/attendance/clock")
    @ResponseStatus(HttpStatus.CREATED)
    public TimeLog clock(@RequestBody ClockRequest r) {
        return attendance.clock(r.employeeId(), r.logType(), r.source() == null ? "MOBILE" : r.source(), r.latitude(),
                r.longitude(), r.deviceId(), r.photoRef());
    }

    @PostMapping("/attendance/manual")
    @ResponseStatus(HttpStatus.CREATED)
    public TimeLog manual(@RequestBody ManualRequest r) {
        return attendance.recordManual(r.employeeId(), r.logType(), r.logTime(), r.remarks());
    }

    @PostMapping("/attendance/logs/{id}/validate")
    public TimeLog validate(@PathVariable UUID id) {
        return attendance.validate(id);
    }

    @PostMapping("/attendance/corrections")
    @ResponseStatus(HttpStatus.CREATED)
    public AttendanceCorrection requestCorrection(@RequestBody CorrectionRequest r) {
        return attendance.requestCorrection(r.employeeId(), r.workDate(), r.requestedIn(), r.requestedOut(), r.reason());
    }

    @PostMapping("/attendance/corrections/{id}/approve")
    public AttendanceCorrection approveCorrection(@PathVariable UUID id, @AuthenticationPrincipal HrmUserPrincipal user) {
        return attendance.approveCorrection(id, user.id());
    }

    @PostMapping("/attendance/corrections/{id}/reject")
    public AttendanceCorrection rejectCorrection(@PathVariable UUID id, @AuthenticationPrincipal HrmUserPrincipal user) {
        return attendance.rejectCorrection(id, user.id());
    }

    @PostMapping("/dtr/process")
    public Map<String, Object> process(@RequestBody ProcessRequest r) {
        return Map.of("processedDays", dtr.process(r.from(), r.to(), r.employeeId()));
    }

    @PostMapping("/leave/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveRequest fileLeave(@RequestBody LeaveFileRequest r) {
        return leave.file(r.employeeId(), r.leaveTypeId(), r.dateFrom(), r.dateTo(), r.days(), r.halfDay(), r.reason());
    }

    @PostMapping("/leave/requests/{id}/approve")
    public LeaveRequest approveLeave(@PathVariable UUID id, @AuthenticationPrincipal HrmUserPrincipal user) {
        return leave.approve(id, user.id());
    }

    @PostMapping("/leave/requests/{id}/reject")
    public LeaveRequest rejectLeave(@PathVariable UUID id, @AuthenticationPrincipal HrmUserPrincipal user) {
        return leave.reject(id, user.id());
    }

    @PostMapping("/leave/requests/{id}/cancel")
    public LeaveRequest cancelLeave(@PathVariable UUID id) {
        return leave.cancel(id);
    }

    @PostMapping("/leave/grants")
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveLedger grant(@RequestBody GrantRequest r) {
        return leave.grant(r.employeeId(), r.leaveTypeId(), r.days(), r.remarks());
    }

    @GetMapping("/leave/balance")
    public Map<String, Object> balance(@RequestParam UUID employeeId, @RequestParam UUID leaveTypeId) {
        return Map.of("employeeId", employeeId, "leaveTypeId", leaveTypeId, "balance", leave.balance(employeeId, leaveTypeId));
    }

    @PostMapping("/overtime/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public OvertimeRequest fileOvertime(@RequestBody OvertimeFileRequest r) {
        return overtime.file(r.employeeId(), r.workDate(), r.minutes(), r.reason());
    }

    @PostMapping("/overtime/requests/{id}/approve")
    public OvertimeRequest approveOvertime(@PathVariable UUID id, @RequestBody(required = false) DecisionRequest r,
                                           @AuthenticationPrincipal HrmUserPrincipal user) {
        return overtime.approve(id, r == null ? null : r.approvedMinutes(), user.id());
    }

    @PostMapping("/overtime/requests/{id}/reject")
    public OvertimeRequest rejectOvertime(@PathVariable UUID id, @AuthenticationPrincipal HrmUserPrincipal user) {
        return overtime.reject(id, user.id());
    }
}
