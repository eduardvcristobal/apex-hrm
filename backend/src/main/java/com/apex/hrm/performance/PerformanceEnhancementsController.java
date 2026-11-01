package com.apex.hrm.performance;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** M5.13-M5.15: HR goal editing, evidence-based development and aggregated reports. */
@RestController
@RequestMapping("/api/v1/performance")
public class PerformanceEnhancementsController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public PerformanceEnhancementsController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc; this.tenants=tenants;
    }
    public record GoalEdit(@NotBlank @Size(max=200) String title,
        @Size(max=4000) String description,@Size(max=160) String metric,
        @NotNull @DecimalMin("0.001") BigDecimal targetValue,
        @NotNull @DecimalMin("0.01") @DecimalMax("100") BigDecimal weight,
        LocalDate dueOn) {}
    public record MilestoneInput(@NotBlank @Size(max=200) String title,
        @Size(max=4000) String description, LocalDate dueOn) {}
    public record MilestoneUpdate(@NotBlank @Pattern(regexp="PLANNED|IN_PROGRESS|COMPLETED|CANCELLED") String status,
        @NotBlank @Size(max=4000) String evidence) {}
    private boolean role(HrmUserPrincipal u,String role) {
        return u.getAuthorities().stream().anyMatch(a->a.getAuthority().equals(role));
    }
    private boolean hr(HrmUserPrincipal u) { return role(u,"ROLE_TENANT_ADMIN") || role(u,"ROLE_HR_ADMIN"); }
    private void auth(HrmUserPrincipal u) {
        if(u==null || !u.isEnabled() || !role(u,"ROLE_MFA_VERIFIED"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Verified MFA required");
    }
    private void hrOnly(HrmUserPrincipal u) {
        auth(u);
        if(!hr(u)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"HR role required");
    }
    private UUID linked(HrmUserPrincipal u) {
        List<UUID> ids=jdbc.query("SELECT employee_id FROM performance_employee_identity WHERE tenant_id=? AND identity_id=?",
          (rs,i)->rs.getObject(1,UUID.class),u.tenantId(),u.id());
        return ids.isEmpty()?null:ids.get(0);
    }
    private boolean canManage(HrmUserPrincipal u,UUID employeeId) {
        if(hr(u)) return true;
        UUID id=linked(u);
        if(id==null) return false;
        if(id.equals(employeeId)) return true;
        return role(u,"ROLE_MANAGER") && Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM personnel_employee WHERE tenant_id=? AND id=? AND supervisor_id=?)
            """,Boolean.class,u.tenantId(),employeeId,id));
    }
    private void assertAccess(HrmUserPrincipal u,UUID employeeId) {
        if(!canManage(u,employeeId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Employee access denied");
    }
    private void audit(HrmUserPrincipal u,String action,String entity,UUID id) {
        jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,?,?,?)",
                u.tenantId(),u.id(),action,entity,id);
    }
    @PutMapping("/goals/{goalId}")
    public void editGoal(@AuthenticationPrincipal HrmUserPrincipal u,@PathVariable UUID goalId,
                          @Valid @RequestBody GoalEdit input) {
        hrOnly(u);
        tenants.inTenant(u.tenantId(),()->{
            int count=jdbc.update("""
               UPDATE performance_goal g SET title=?,description=?,metric=?,target_value=?,weight=?,due_on=?,updated_at=now()
               WHERE g.tenant_id=? AND g.id=? AND g.status='ACTIVE' AND EXISTS(
                 SELECT 1 FROM performance_cycle c WHERE c.tenant_id=g.tenant_id
                   AND c.id=g.cycle_id AND c.status='ACTIVE')
               """,input.title().trim(),input.description(),input.metric(),input.targetValue(),input.weight(),
               input.dueOn(),u.tenantId(),goalId);
            if(count!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Only active goals in active cycles can be edited");
            audit(u,"PERFORMANCE_GOAL_EDITED","performance_goal",goalId);
            return null;
        });
    }
    @GetMapping("/plans/{planId}/milestones")
    public List<Map<String,Object>> milestones(@AuthenticationPrincipal HrmUserPrincipal u,@PathVariable UUID planId) {
        auth(u);
        return tenants.inTenant(u.tenantId(),()->{
            List<UUID> employees=jdbc.query("""
                SELECT r.employee_id FROM performance_development_plan p JOIN performance_review r
                  ON r.tenant_id=p.tenant_id AND r.id=p.review_id
                WHERE p.tenant_id=? AND p.id=?
                """,(rs,i)->rs.getObject(1,UUID.class),u.tenantId(),planId);
            if(employees.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Plan not found");
            assertAccess(u,employees.get(0));
            return jdbc.queryForList("""
                SELECT id,title,description,due_on AS "dueOn",status,completed_at AS "completedAt"
                FROM performance_plan_milestone WHERE tenant_id=? AND plan_id=?
                ORDER BY created_at,id LIMIT 200
                """,u.tenantId(),planId);
        });
    }
    @PostMapping("/plans/{planId}/milestones")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> addMilestone(@AuthenticationPrincipal HrmUserPrincipal u,@PathVariable UUID planId,
                                           @Valid @RequestBody MilestoneInput input) {
        auth(u);
        return tenants.inTenant(u.tenantId(),()->{
            List<UUID> employees=jdbc.query("""
                SELECT r.employee_id FROM performance_development_plan p JOIN performance_review r
                   ON r.tenant_id=p.tenant_id AND r.id=p.review_id
                WHERE p.tenant_id=? AND p.id=? AND p.status IN ('PLANNED','IN_PROGRESS')
                FOR UPDATE OF p
                """,(rs,i)->rs.getObject(1,UUID.class),u.tenantId(),planId);
            if(employees.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Open development plan required");
            assertAccess(u,employees.get(0));
            UUID id=jdbc.queryForObject("""
                INSERT INTO performance_plan_milestone(tenant_id,plan_id,title,description,due_on,created_by)
                VALUES(?,?,?,?,?,?) RETURNING id
                """,UUID.class,u.tenantId(),planId,input.title().trim(),input.description(),input.dueOn(),u.id());
            audit(u,"PERFORMANCE_MILESTONE_CREATED","performance_plan_milestone",id);
            return Map.of("id",id,"status","PLANNED");
        });
    }
    @PostMapping("/milestones/{milestoneId}/status")
    public void updateMilestone(@AuthenticationPrincipal HrmUserPrincipal u,@PathVariable UUID milestoneId,
                                @Valid @RequestBody MilestoneUpdate input) {
        auth(u);
        tenants.inTenant(u.tenantId(),()->{
            List<Map<String,Object>> rows=jdbc.queryForList("""
                SELECT m.status,m.plan_id,r.employee_id FROM performance_plan_milestone m
                JOIN performance_development_plan p ON p.tenant_id=m.tenant_id AND p.id=m.plan_id
                JOIN performance_review r ON r.tenant_id=p.tenant_id AND r.id=p.review_id
                WHERE m.tenant_id=? AND m.id=? AND p.status IN ('PLANNED','IN_PROGRESS')
                FOR UPDATE OF m
                """,u.tenantId(),milestoneId);
            if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Open milestone not found");
            Map<String,Object> item=rows.get(0);
            assertAccess(u,(UUID)item.get("employee_id"));
            String old=(String)item.get("status"), next=input.status();
            boolean valid=switch(old) {
                case "PLANNED" -> next.equals("IN_PROGRESS")||next.equals("CANCELLED");
                case "IN_PROGRESS" -> next.equals("COMPLETED")||next.equals("CANCELLED");
                default -> false;
            };
            if(!valid) throw new ResponseStatusException(HttpStatus.CONFLICT,"Invalid milestone transition");
            jdbc.update("""
                UPDATE performance_plan_milestone SET status=?,updated_at=now(),
                    completed_at=CASE WHEN ?='COMPLETED' THEN now() ELSE NULL END
                WHERE tenant_id=? AND id=?
                """,next,next,u.tenantId(),milestoneId);
            jdbc.update("""
                INSERT INTO performance_milestone_evidence(tenant_id,milestone_id,note,recorded_by)
                VALUES(?,?,?,?)
                """,u.tenantId(),milestoneId,input.evidence().trim(),u.id());
            audit(u,"PERFORMANCE_MILESTONE_UPDATED","performance_plan_milestone",milestoneId);
            return null;
        });
    }
    @GetMapping("/milestones/{milestoneId}/evidence")
    public List<Map<String,Object>> evidence(@AuthenticationPrincipal HrmUserPrincipal u,@PathVariable UUID milestoneId) {
        auth(u);
        return tenants.inTenant(u.tenantId(),()->{
            List<UUID> employees=jdbc.query("""
                SELECT r.employee_id FROM performance_plan_milestone m
                JOIN performance_development_plan p ON p.tenant_id=m.tenant_id AND p.id=m.plan_id
                JOIN performance_review r ON r.tenant_id=p.tenant_id AND r.id=p.review_id
                WHERE m.tenant_id=? AND m.id=?
                """,(rs,i)->rs.getObject(1,UUID.class),u.tenantId(),milestoneId);
            if(employees.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            assertAccess(u,employees.get(0));
            return jdbc.queryForList("""
                SELECT note,recorded_at AS "recordedAt" FROM performance_milestone_evidence
                WHERE tenant_id=? AND milestone_id=? ORDER BY recorded_at DESC LIMIT 100
                """,u.tenantId(),milestoneId);
        });
    }
    @GetMapping("/reports/summary")
    public List<Map<String,Object>> summary(@AuthenticationPrincipal HrmUserPrincipal u,@RequestParam UUID companyId) {
        hrOnly(u);
        return tenants.inTenant(u.tenantId(),()->jdbc.queryForList("""
            SELECT c.id AS "cycleId",c.name AS "cycleName",c.status AS "cycleStatus",
              COUNT(DISTINCT g.id) AS "goalCount",
              COUNT(DISTINCT r.id) AS "reviewCount",
              COUNT(DISTINCT r.id) FILTER (WHERE r.status='FINALIZED') AS "finalizedCount",
              COUNT(DISTINCT r.id) FILTER (WHERE r.status='DRAFT') AS "draftCount",
              COUNT(DISTINCT r.id) FILTER (WHERE r.status='SUBMITTED') AS "submittedCount",
              COUNT(DISTINCT r.id) FILTER (WHERE r.status='ACKNOWLEDGED') AS "acknowledgedCount",
              ROUND(AVG(r.rating),2) AS "averageRating"
            FROM performance_cycle c
            LEFT JOIN performance_goal g ON g.tenant_id=c.tenant_id AND g.cycle_id=c.id AND g.status<>'CANCELLED'
            LEFT JOIN performance_review r ON r.tenant_id=c.tenant_id AND r.cycle_id=c.id
            WHERE c.tenant_id=? AND c.company_id=?
            GROUP BY c.id,c.name,c.status,c.starts_on ORDER BY c.starts_on DESC LIMIT 100
            """,u.tenantId(),companyId));
    }
}
