package com.apex.hrm.timekeeping.service;

import com.apex.hrm.platform.repo.TenantRepository;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Purges expired attendance photos only when a tenant has explicitly configured a retention period. */
@Component
public class AttendanceEvidenceRetentionScheduler {
    private final TenantRepository tenants;
    private final TenantJdbcExecutor tenantJdbc;
    private final JdbcTemplate jdbc;

    public AttendanceEvidenceRetentionScheduler(TenantRepository tenants, TenantJdbcExecutor tenantJdbc, JdbcTemplate jdbc) {
        this.tenants = tenants;
        this.tenantJdbc = tenantJdbc;
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 40 2 * * *", zone = "Asia/Manila")
    public void purgeExpiredEvidence() {
        for (var tenant : tenants.findAll()) {
            if (!"ACTIVE".equals(tenant.getStatus())) continue;
            UUID tenantId = tenant.getId();
            tenantJdbc.inTenant(tenantId, () -> {
                List<Integer> policies = jdbc.query("SELECT retention_days FROM tenant_attendance_evidence_policy WHERE tenant_id=? AND enabled",
                        (rs, row) -> rs.getInt(1), tenantId);
                if (policies.isEmpty()) return 0;
                int retentionDays = policies.get(0);
                return jdbc.update("""
                    WITH purged AS (
                      DELETE FROM attendance_photo p USING tenant_attendance_evidence_policy e
                      WHERE p.tenant_id=? AND e.tenant_id=p.tenant_id AND e.enabled AND e.retention_days=?
                        AND p.captured_at < now() - make_interval(days => e.retention_days) AND NOT p.legal_hold
                      RETURNING p.id
                    )
                    INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id,metadata)
                    SELECT ?,NULL,'ATTENDANCE_EVIDENCE_RETENTION_PURGED','attendance_photo',id,
                      jsonb_build_object('retention_days',?) FROM purged
                    """, tenantId, retentionDays, tenantId, retentionDays);
            });
        }
    }
}
