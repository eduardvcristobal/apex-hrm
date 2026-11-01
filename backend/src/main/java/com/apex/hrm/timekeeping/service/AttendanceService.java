package com.apex.hrm.timekeeping.service;

import com.apex.hrm.common.BusinessException;
import com.apex.hrm.common.NotFoundException;
import com.apex.hrm.org.domain.WorkLocation;
import com.apex.hrm.org.repo.WorkLocationRepository;
import com.apex.hrm.personnel.domain.Employee;
import com.apex.hrm.personnel.domain.EmploymentRecord;
import com.apex.hrm.personnel.repo.EmployeeRepository;
import com.apex.hrm.personnel.repo.EmploymentRecordRepository;
import com.apex.hrm.timekeeping.domain.AttendanceCorrection;
import com.apex.hrm.timekeeping.domain.DtrDay;
import com.apex.hrm.timekeeping.domain.TimeLog;
import com.apex.hrm.timekeeping.repo.AttendanceCorrectionRepository;
import com.apex.hrm.timekeeping.repo.DtrDayRepository;
import com.apex.hrm.timekeeping.repo.TimeLogRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttendanceService {
    private final TimeLogRepository logs;
    private final AttendanceCorrectionRepository corrections;
    private final DtrDayRepository dtrDays;
    private final EmployeeRepository employees;
    private final EmploymentRecordRepository employment;
    private final WorkLocationRepository locations;
    private final ZoneId zone;

    public AttendanceService(TimeLogRepository logs, AttendanceCorrectionRepository corrections, DtrDayRepository dtrDays,
                             EmployeeRepository employees, EmploymentRecordRepository employment,
                             WorkLocationRepository locations, @Value("${hrm.timezone:Asia/Manila}") String timezone) {
        this.logs = logs;
        this.corrections = corrections;
        this.dtrDays = dtrDays;
        this.employees = employees;
        this.employment = employment;
        this.locations = locations;
        this.zone = ZoneId.of(timezone);
    }

    /** Mobile/web punch. The server clock is authoritative; the device time is never trusted. */
    @Transactional
    public TimeLog clock(UUID employeeId, String logType, String source, BigDecimal lat, BigDecimal lon,
                         String deviceId, String photoRef) {
        Employee emp = activeEmployee(employeeId);
        requireLogType(logType);
        if (!"MOBILE".equals(source) && !"WEB".equals(source)) {
            throw new BusinessException("source must be MOBILE or WEB for self-service punches");
        }
        Instant now = Instant.now();
        logs.findFirstByEmployeeIdOrderByLogTimeDesc(employeeId).ifPresent(last -> {
            if (last.getLogType().equals(logType) && Duration.between(last.getLogTime(), now).getSeconds() < 60) {
                throw new BusinessException("Duplicate " + logType + " punch within 60 seconds");
            }
        });
        TimeLog log = new TimeLog();
        log.setEmployeeId(employeeId);
        log.setLogTime(now);
        log.setLogType(logType);
        log.setSource(source);
        log.setLatitude(lat);
        log.setLongitude(lon);
        log.setDeviceId(deviceId);
        log.setPhotoRef(photoRef);
        applyGeofence(log, emp, now);
        assertNotLocked(employeeId, now);
        return logs.save(log);
    }

    /** HR-entered punch with an explicit time. Always recorded as MANUAL. */
    @Transactional
    public TimeLog recordManual(UUID employeeId, String logType, Instant logTime, String remarks) {
        activeEmployee(employeeId);
        requireLogType(logType);
        if (logTime == null) {
            throw new BusinessException("logTime is required");
        }
        assertNotLocked(employeeId, logTime);
        TimeLog log = new TimeLog();
        log.setEmployeeId(employeeId);
        log.setLogTime(logTime);
        log.setLogType(logType);
        log.setSource("MANUAL");
        log.setRemarks(remarks);
        return logs.save(log);
    }

    /** Accepts a flagged punch (e.g. outside the geofence) so DTR processing will count it. */
    @Transactional
    public TimeLog validate(UUID logId) {
        TimeLog log = logs.findById(logId).orElseThrow(() -> new NotFoundException("Time log", logId));
        assertNotLocked(log.getEmployeeId(), log.getLogTime());
        log.setStatus("VALID");
        return logs.save(log);
    }

    @Transactional
    public AttendanceCorrection requestCorrection(UUID employeeId, LocalDate workDate, Instant in, Instant out, String reason) {
        activeEmployee(employeeId);
        if (workDate == null || (in == null && out == null)) {
            throw new BusinessException("workDate and at least one of requestedIn / requestedOut are required");
        }
        AttendanceCorrection c = new AttendanceCorrection();
        c.setEmployeeId(employeeId);
        c.setWorkDate(workDate);
        c.setRequestedIn(in);
        c.setRequestedOut(out);
        c.setReason(reason);
        return corrections.save(c);
    }

    @Transactional
    public AttendanceCorrection approveCorrection(UUID id, UUID approverId) {
        AttendanceCorrection c = pendingCorrection(id);
        if (c.getRequestedIn() != null) {
            recordManual(c.getEmployeeId(), "IN", c.getRequestedIn(), "Correction " + id);
        }
        if (c.getRequestedOut() != null) {
            recordManual(c.getEmployeeId(), "OUT", c.getRequestedOut(), "Correction " + id);
        }
        c.setStatus("APPROVED");
        c.setApprovedBy(approverId);
        c.setApprovedAt(Instant.now());
        return corrections.save(c);
    }

    @Transactional
    public AttendanceCorrection rejectCorrection(UUID id, UUID approverId) {
        AttendanceCorrection c = pendingCorrection(id);
        c.setStatus("REJECTED");
        c.setApprovedBy(approverId);
        c.setApprovedAt(Instant.now());
        return corrections.save(c);
    }

    private AttendanceCorrection pendingCorrection(UUID id) {
        AttendanceCorrection c = corrections.findById(id).orElseThrow(() -> new NotFoundException("Correction", id));
        if (!"PENDING".equals(c.getStatus())) {
            throw new BusinessException("Correction is already " + c.getStatus());
        }
        return c;
    }

    private Employee activeEmployee(UUID employeeId) {
        Employee e = employees.findById(employeeId).orElseThrow(() -> new NotFoundException("Employee", employeeId));
        if ("SEPARATED".equals(e.getStatus())) {
            throw new BusinessException("Employee is separated");
        }
        return e;
    }

    private static void requireLogType(String type) {
        if (!"IN".equals(type) && !"OUT".equals(type)) {
            throw new BusinessException("logType must be IN or OUT");
        }
    }

    /** Once a payroll period is locked, attendance changes must go through a payroll adjustment instead. */
    private void assertNotLocked(UUID employeeId, Instant at) {
        LocalDate day = at.atZone(zone).toLocalDate();
        Optional<DtrDay> dtr = dtrDays.findByEmployeeIdAndWorkDate(employeeId, day);
        if (dtr.isPresent() && "LOCKED".equals(dtr.get().getStatus())) {
            throw new BusinessException("Attendance for " + day + " is locked; file a payroll adjustment instead");
        }
    }

    private void applyGeofence(TimeLog log, Employee emp, Instant at) {
        LocalDate today = at.atZone(zone).toLocalDate();
        Optional<EmploymentRecord> rec = employment
                .findFirstByEmployeeIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(emp.getId(), today);
        if (rec.isEmpty() || rec.get().getWorkLocationId() == null) {
            return;
        }
        WorkLocation loc = locations.findById(rec.get().getWorkLocationId()).orElse(null);
        if (loc == null || loc.getLatitude() == null || loc.getLongitude() == null || loc.getGeofenceRadiusM() == null) {
            return;
        }
        if (log.getLatitude() == null || log.getLongitude() == null) {
            log.setStatus("FLAGGED");
            log.setRemarks("No GPS fix supplied");
            return;
        }
        double meters = haversineMeters(loc.getLatitude().doubleValue(), loc.getLongitude().doubleValue(),
                log.getLatitude().doubleValue(), log.getLongitude().doubleValue());
        if (meters > loc.getGeofenceRadiusM()) {
            log.setStatus("FLAGGED");
            log.setRemarks("Outside geofence by " + Math.round(meters - loc.getGeofenceRadiusM()) + " m");
        }
    }

    static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }
}
