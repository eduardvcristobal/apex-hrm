package com.apex.hrm.learning;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Aggregated reporting, never an unrestricted employee directory export. */
@RestController
@RequestMapping("/api/v1/learning/reports")
public class LearningReportController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public LearningReportController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc;this.tenants=tenants;
    }

    @GetMapping("/summary")
    public Map<String,Object> summary(@AuthenticationPrincipal HrmUserPrincipal user) {
        return tenants.inTenant(user.tenantId(),()->{
            Map<String,Object> totals=jdbc.queryForMap("""
                SELECT count(*) AS "enrollments",
                    count(*) FILTER(WHERE status='COMPLETED') AS "completed",
                    count(*) FILTER(WHERE status='IN_PROGRESS') AS "inProgress",
                    count(*) FILTER(WHERE status='ENROLLED') AS "notStarted",
                    count(*) FILTER(WHERE status='CANCELLED') AS "cancelled"
                FROM ld_enrollment WHERE tenant_id=?
                """,user.tenantId());
            List<Map<String,Object>> courses=jdbc.queryForList("""
                SELECT c.id,c.title,
                    count(e.id) AS "enrolled",
                    count(e.id) FILTER(WHERE e.status='COMPLETED') AS "completed"
                FROM ld_course c LEFT JOIN ld_enrollment e ON e.tenant_id=c.tenant_id AND e.course_id=c.id
                WHERE c.tenant_id=? GROUP BY c.id,c.title ORDER BY c.title LIMIT 500
                """,user.tenantId());
            List<Map<String,Object>> expiring=jdbc.queryForList("""
                SELECT c.id,c.title,c.issuer,c.employee_id AS "employeeId",
                    p.employee_number AS "employeeNumber",c.expires_on AS "expiresOn"
                FROM ld_certification c JOIN personnel_employee p
                    ON p.tenant_id=c.tenant_id AND p.id=c.employee_id
                WHERE c.tenant_id=? AND c.expires_on IS NOT NULL
                    AND c.expires_on BETWEEN current_date AND current_date+interval '60 days'
                ORDER BY c.expires_on LIMIT 500
                """,user.tenantId());
            List<Map<String,Object>> gaps=jdbc.queryForList("""
                SELECT s.id,s.name AS "skillName",
                    count(es.id) AS "assessedEmployees",
                    count(es.id) FILTER(WHERE es.level < 3) AS "belowLevelThree"
                FROM ld_skill s LEFT JOIN ld_employee_skill es ON es.tenant_id=s.tenant_id AND es.skill_id=s.id
                WHERE s.tenant_id=? GROUP BY s.id,s.name ORDER BY s.name LIMIT 500
                """,user.tenantId());
            return Map.of("totals",totals,"courses",courses,"expiringCertifications",expiring,"skillDistribution",gaps);
        });
    }
}
