package com.apex.hrm.timekeeping.web;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Tenant policy for biometric attendance evidence; no automatic deletion until HR configures a term. */
@RestController
@RequestMapping("/api/attendance/evidence-retention")
public class AttendanceEvidenceRetentionController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;

    public AttendanceEvidenceRetentionController(JdbcTemplate jdbc, TenantJdbcExecutor tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    public record Policy(Integer retentionDays, boolean enabled, String rationale, Instant updatedAt) {}
    public record Update(@Min(1) @Max(3650) Integer retentionDays, @Size(max = 500) String rationale) {}
    public record Hold(@Size(max = 500) String reason) {}
    public record HoldRecord(UUID timeLogId, UUID employeeId, Instant capturedAt, String reason) {}

    @GetMapping
    public Policy get(@AuthenticationPrincipal HrmUserPrincipal user) {
        return tenants.inTenant(user.tenantId(), () -> read(user.tenantId()));
    }

    @GetMapping("/holds")
    public List<HoldRecord> holds(@AuthenticationPrincipal HrmUserPrincipal user) {
        return tenants.inTenant(user.tenantId(), () -> jdbc.query("""
            SELECT time_log_id,employee_id,captured_at,legal_hold_reason FROM attendance_photo
            WHERE tenant_id=? AND legal_hold ORDER BY legal_hold_updated_at DESC LIMIT 500
            """, (rs, row) -> new HoldRecord(rs.getObject("time_log_id", UUID.class),
                rs.getObject("employee_id", UUID.class), rs.getObject("captured_at", Instant.class),
                rs.getString("legal_hold_reason")), user.tenantId()));
    }

    @PutMapping
    public Policy update(@AuthenticationPrincipal HrmUserPrincipal user, @Valid @RequestBody Update input) {
        if (input.retentionDays() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Retention days are required; use DELETE to disable automatic purging");
        }
        return tenants.inTenant(user.tenantId(), () -> {
            jdbc.update("""
                INSERT INTO tenant_attendance_evidence_policy(tenant_id,retention_days,enabled,rationale,updated_by)
                VALUES(?,?,true,?,?) ON CONFLICT(tenant_id) DO UPDATE SET retention_days=excluded.retention_days,
                  enabled=true,rationale=excluded.rationale,updated_by=excluded.updated_by,updated_at=now()
                """, user.tenantId(), input.retentionDays(), clean(input.rationale()), user.id());
            audit(user, "ATTENDANCE_EVIDENCE_RETENTION_SET", null);
            return read(user.tenantId());
        });
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@AuthenticationPrincipal HrmUserPrincipal user) {
        tenants.inTenant(user.tenantId(), () -> {
            jdbc.update("""
                INSERT INTO tenant_attendance_evidence_policy(tenant_id,enabled,updated_by)
                VALUES(?,false,?) ON CONFLICT(tenant_id) DO UPDATE SET enabled=false,updated_by=excluded.updated_by,updated_at=now()
                """, user.tenantId(), user.id());
            audit(user, "ATTENDANCE_EVIDENCE_RETENTION_DISABLED", null);
            return null;
        });
    }

    @PutMapping("/{timeLogId}/legal-hold")
    public void hold(@AuthenticationPrincipal HrmUserPrincipal user, @PathVariable UUID timeLogId,
                     @Valid @RequestBody Hold input) {
        tenants.inTenant(user.tenantId(), () -> {
            int updated = jdbc.update("""
                UPDATE attendance_photo SET legal_hold=true,legal_hold_reason=?,legal_hold_updated_by=?,legal_hold_updated_at=now()
                WHERE tenant_id=? AND time_log_id=?
                """, clean(input.reason()), user.id(), user.tenantId(), timeLogId);
            if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Attendance photo not found");
            audit(user, "ATTENDANCE_EVIDENCE_LEGAL_HOLD_SET", timeLogId);
            return null;
        });
    }

    @DeleteMapping("/{timeLogId}/legal-hold")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@AuthenticationPrincipal HrmUserPrincipal user, @PathVariable UUID timeLogId) {
        tenants.inTenant(user.tenantId(), () -> {
            int updated = jdbc.update("""
                UPDATE attendance_photo SET legal_hold=false,legal_hold_reason=NULL,legal_hold_updated_by=?,legal_hold_updated_at=now()
                WHERE tenant_id=? AND time_log_id=?
                """, user.id(), user.tenantId(), timeLogId);
            if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Attendance photo not found");
            audit(user, "ATTENDANCE_EVIDENCE_LEGAL_HOLD_RELEASED", timeLogId);
            return null;
        });
    }

    private Policy read(UUID tenant) {
        List<Policy> result = jdbc.query("""
            SELECT retention_days,enabled,rationale,updated_at FROM tenant_attendance_evidence_policy WHERE tenant_id=?
            """, (rs, row) -> new Policy((Integer) rs.getObject("retention_days"), rs.getBoolean("enabled"),
            rs.getString("rationale"), rs.getObject("updated_at", Instant.class)), tenant);
        return result.isEmpty() ? new Policy(null, false, null, null) : result.get(0);
    }

    private void audit(HrmUserPrincipal user, String action, UUID entityId) {
        jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,?,'attendance_photo',?)",
                user.tenantId(), user.id(), action, entityId);
    }

    private static String clean(String value) { return value == null ? null : value.trim(); }
}
