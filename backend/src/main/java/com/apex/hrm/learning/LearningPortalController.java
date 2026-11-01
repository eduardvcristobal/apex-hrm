package com.apex.hrm.learning;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Portal ownership is resolved exclusively from the verified login identity, never a client employee ID. */
@RestController
@RequestMapping("/api/v1/learning")
public class LearningPortalController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public LearningPortalController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record AccountLink(@NotNull UUID identityId,@NotNull UUID employeeId) {}

    @PutMapping("/accounts")
    public Map<String,UUID> link(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody AccountLink input) {
        return tenants.inTenant(user.tenantId(),()->{
            Boolean valid=jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM auth_identity a
                    JOIN personnel_employee e ON e.tenant_id=a.tenant_id
                    WHERE a.tenant_id=? AND a.id=? AND a.enabled=true AND e.id=?)
                """,Boolean.class,user.tenantId(),input.identityId(),input.employeeId());
            if(!Boolean.TRUE.equals(valid)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Account or employee not found");
            jdbc.update("""
                INSERT INTO ld_employee_account(tenant_id,identity_id,employee_id,linked_by)
                VALUES(?,?,?,?)
                ON CONFLICT(tenant_id,identity_id) DO UPDATE SET employee_id=EXCLUDED.employee_id,
                    linked_at=now(),linked_by=EXCLUDED.linked_by
                """,user.tenantId(),input.identityId(),input.employeeId(),user.id());
            jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,'LD_ACCOUNT_LINKED','personnel_employee',?)",
                user.tenantId(),user.id(),input.employeeId());
            return Map.of("identityId",input.identityId(),"employeeId",input.employeeId());
        });
    }

    @GetMapping("/me")
    public Map<String,Object> mine(@AuthenticationPrincipal HrmUserPrincipal user) {
        return tenants.inTenant(user.tenantId(),()->{
            List<UUID> matches=jdbc.query("""
                SELECT employee_id FROM ld_employee_account WHERE tenant_id=? AND identity_id=?
                """,(rs,i)->rs.getObject(1,UUID.class),user.tenantId(),user.id());
            if(matches.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"No employee profile linked to your account");
            UUID employee=matches.get(0);
            return Map.of(
                "enrollments",jdbc.queryForList("""
                    SELECT e.id,c.title AS "courseTitle",c.delivery_mode AS "deliveryMode",
                        e.status,e.enrolled_on AS "enrolledOn",e.completed_on AS "completedOn",e.score
                    FROM ld_enrollment e JOIN ld_course c ON c.tenant_id=e.tenant_id AND c.id=e.course_id
                    WHERE e.tenant_id=? AND e.employee_id=? ORDER BY e.enrolled_on DESC LIMIT 500
                    """,user.tenantId(),employee),
                "skills",jdbc.queryForList("""
                    SELECT s.name AS "skillName",s.category,es.level,es.assessed_on AS "assessedOn"
                    FROM ld_employee_skill es JOIN ld_skill s ON s.tenant_id=es.tenant_id AND s.id=es.skill_id
                    WHERE es.tenant_id=? AND es.employee_id=? ORDER BY s.name LIMIT 500
                    """,user.tenantId(),employee),
                "certifications",jdbc.queryForList("""
                    SELECT title,issuer,credential_ref AS "credentialRef",issued_on AS "issuedOn",expires_on AS "expiresOn"
                    FROM ld_certification WHERE tenant_id=? AND employee_id=? ORDER BY issued_on DESC LIMIT 500
                    """,user.tenantId(),employee)
            );
        });
    }
}
