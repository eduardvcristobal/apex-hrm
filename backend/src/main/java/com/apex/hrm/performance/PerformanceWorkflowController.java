package com.apex.hrm.performance;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Employee/manager workflows use explicit account linkage and supervisor hierarchy. */
@RestController
@RequestMapping("/api/v1/performance")
public class PerformanceWorkflowController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public PerformanceWorkflowController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc; this.tenants=tenants;
    }
    public record LinkInput(@NotNull UUID employeeId,@NotNull UUID identityId) {}
    public record ProgressInput(@NotNull @DecimalMin("0") BigDecimal actualValue,
                                @Size(max=2000) String note) {}
    public record SelfAssessmentInput(@NotNull @DecimalMin("1") @DecimalMax("5") BigDecimal rating,
                                      @NotBlank @Size(max=10000) String assessment) {}
    public record PlanStatusInput(@NotBlank @Pattern(regexp="PLANNED|IN_PROGRESS|COMPLETED|CANCELLED") String status) {}
    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN,"Not authorized for this employee");
    }
    private boolean has(HrmUserPrincipal user,String role) {
        return user.getAuthorities().stream().anyMatch(a->a.getAuthority().equals(role));
    }
    private void authenticated(HrmUserPrincipal user) {
        if(user==null || !user.isEnabled() || !has(user,"ROLE_MFA_VERIFIED")) throw denied();
    }
    private boolean hr(HrmUserPrincipal user) {
        return has(user,"ROLE_TENANT_ADMIN") || has(user,"ROLE_HR_ADMIN");
    }
    private boolean exists(String sql,Object... args) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args));
    }
    private UUID employee(HrmUserPrincipal user) {
        List<UUID> ids=jdbc.query("SELECT employee_id FROM performance_employee_identity WHERE tenant_id=? AND identity_id=?",
             (rs,i)->rs.getObject(1,UUID.class),user.tenantId(),user.id());
        return ids.isEmpty()?null:ids.get(0);
    }
    private boolean canSee(HrmUserPrincipal user,UUID targetId) {
        if(hr(user)) return true;
        UUID caller=employee(user);
        if(caller==null) return false;
        if(caller.equals(targetId)) return true;
        return has(user,"ROLE_MANAGER") && exists("""
            SELECT EXISTS(SELECT 1 FROM personnel_employee
               WHERE tenant_id=? AND id=? AND supervisor_id=?)
            """,user.tenantId(),targetId,caller);
    }
    private void requireEmployeeAccess(HrmUserPrincipal user,UUID targetId) {
        if(!canSee(user,targetId)) throw denied();
    }
    private void requireHr(HrmUserPrincipal user) {
        authenticated(user);
        if(!hr(user)) throw denied();
    }
    private void audit(HrmUserPrincipal user,String action,String entity,UUID id) {
        jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,?,?,?)",
            user.tenantId(),user.id(),action,entity,id);
    }
    @PostMapping("/identity-links")
    @ResponseStatus(HttpStatus.CREATED)
    public void link(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody LinkInput input) {
        requireHr(user);
        tenants.inTenant(user.tenantId(),()->{
            if(!exists("SELECT EXISTS(SELECT 1 FROM personnel_employee WHERE tenant_id=? AND id=?)",user.tenantId(),input.employeeId())
               || !exists("SELECT EXISTS(SELECT 1 FROM auth_identity WHERE tenant_id=? AND id=? AND enabled)",user.tenantId(),input.identityId()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Employee or identity invalid");
            int n=jdbc.update("""
               INSERT INTO performance_employee_identity(tenant_id,employee_id,identity_id,linked_by)
               VALUES(?,?,?,?) ON CONFLICT DO NOTHING
               """,user.tenantId(),input.employeeId(),input.identityId(),user.id());
            if(n!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Employee or identity is already linked");
            audit(user,"PERFORMANCE_IDENTITY_LINKED","personnel_employee",input.employeeId());
            return null;
        });
    }
    @GetMapping("/my/goals")
    public List<Map<String,Object>> myGoals(@AuthenticationPrincipal HrmUserPrincipal user) {
        authenticated(user);
        return tenants.inTenant(user.tenantId(),()->{
            UUID id=employee(user);
            if(id==null) throw denied();
            return jdbc.queryForList("""
               SELECT g.id,g.title,g.metric,g.target_value AS "targetValue",
                 g.actual_value AS "actualValue",g.weight,g.status,g.due_on AS "dueOn"
               FROM performance_goal g JOIN performance_cycle c
                 ON c.tenant_id=g.tenant_id AND c.id=g.cycle_id
               WHERE g.tenant_id=? AND g.employee_id=? AND c.status IN ('ACTIVE','CLOSED')
               ORDER BY g.created_at DESC LIMIT 200
               """,user.tenantId(),id);
        });
    }
    @GetMapping("/team/goals")
    public List<Map<String,Object>> teamGoals(@AuthenticationPrincipal HrmUserPrincipal user) {
        authenticated(user);
        if(!hr(user)&&!has(user,"ROLE_MANAGER")) throw denied();
        return tenants.inTenant(user.tenantId(),()->{
            UUID manager=employee(user);
            if(!hr(user)&&manager==null) throw denied();
            if(hr(user)) return jdbc.queryForList("""
                SELECT g.id,g.employee_id AS "employeeId",g.title,g.status,g.actual_value AS "actualValue"
                FROM performance_goal g WHERE g.tenant_id=? ORDER BY g.created_at DESC LIMIT 500
                """,user.tenantId());
            return jdbc.queryForList("""
                SELECT g.id,g.employee_id AS "employeeId",g.title,g.status,g.actual_value AS "actualValue"
                FROM performance_goal g JOIN personnel_employee e
                  ON e.tenant_id=g.tenant_id AND e.id=g.employee_id
                WHERE g.tenant_id=? AND e.supervisor_id=?
                ORDER BY g.created_at DESC LIMIT 500
                """,user.tenantId(),manager);
        });
    }
    @PostMapping("/goals/{goalId}/progress")
    @ResponseStatus(HttpStatus.CREATED)
    public void progress(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID goalId,
                         @Valid @RequestBody ProgressInput input) {
        authenticated(user);
        tenants.inTenant(user.tenantId(),()->{
            List<UUID> rows=jdbc.query("""
              SELECT g.employee_id FROM performance_goal g JOIN performance_cycle c
                ON c.tenant_id=g.tenant_id AND c.id=g.cycle_id
              WHERE g.tenant_id=? AND g.id=? AND g.status='ACTIVE' AND c.status='ACTIVE'
              FOR UPDATE OF g
              """,(rs,i)->rs.getObject(1,UUID.class),user.tenantId(),goalId);
            if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Goal is not active");
            requireEmployeeAccess(user,rows.get(0));
            jdbc.update("""
                INSERT INTO performance_goal_progress(tenant_id,goal_id,actual_value,note,recorded_by)
                VALUES(?,?,?,?,?)
                """,user.tenantId(),goalId,input.actualValue(),input.note(),user.id());
            jdbc.update("UPDATE performance_goal SET actual_value=?,updated_at=now() WHERE tenant_id=? AND id=?",
                input.actualValue(),user.tenantId(),goalId);
            audit(user,"PERFORMANCE_GOAL_PROGRESS","performance_goal",goalId);
            return null;
        });
    }
    @GetMapping("/goals/{goalId}/progress")
    public List<Map<String,Object>> history(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID goalId) {
        authenticated(user);
        return tenants.inTenant(user.tenantId(),()->{
            List<UUID> rows=jdbc.query("SELECT employee_id FROM performance_goal WHERE tenant_id=? AND id=?",
              (rs,i)->rs.getObject(1,UUID.class),user.tenantId(),goalId);
            if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            requireEmployeeAccess(user,rows.get(0));
            return jdbc.queryForList("""
                SELECT actual_value AS "actualValue",note,recorded_at AS "recordedAt"
                FROM performance_goal_progress WHERE tenant_id=? AND goal_id=?
                ORDER BY recorded_at DESC LIMIT 100
                """,user.tenantId(),goalId);
        });
    }
    public record ReviewDraftInput(@NotNull UUID cycleId,@NotNull UUID employeeId) {}
    public record ManagerReviewInput(@NotNull @DecimalMin("1") @DecimalMax("5") BigDecimal rating,
                                     @Size(max=5000) String strengths,@Size(max=5000) String improvements,
                                     @Size(max=5000) String comments) {}
    @PostMapping("/reviews/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> draftReview(@AuthenticationPrincipal HrmUserPrincipal user,
             @Valid @RequestBody ReviewDraftInput input) {
        authenticated(user);
        if(!hr(user)&&!has(user,"ROLE_MANAGER")) throw denied();
        return tenants.inTenant(user.tenantId(),()->{
            if(!exists("""
                SELECT EXISTS(SELECT 1 FROM performance_cycle c JOIN personnel_employee e
                   ON e.tenant_id=c.tenant_id AND e.company_id=c.company_id
                   WHERE c.tenant_id=? AND c.id=? AND c.status='ACTIVE' AND e.id=?)
                """,user.tenantId(),input.cycleId(),input.employeeId()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Employee and active review cycle must share a company");
            if(!hr(user)) {
                UUID manager=employee(user);
                if(manager==null || !exists("""
                    SELECT EXISTS(SELECT 1 FROM personnel_employee
                      WHERE tenant_id=? AND id=? AND supervisor_id=?)
                    """,user.tenantId(),input.employeeId(),manager)) throw denied();
            }
            UUID id=jdbc.queryForObject("""
                INSERT INTO performance_review(tenant_id,cycle_id,employee_id,reviewer_id,status)
                VALUES(?,?,?,?,'DRAFT') RETURNING id
                """,UUID.class,user.tenantId(),input.cycleId(),input.employeeId(),user.id());
            audit(user,"PERFORMANCE_REVIEW_DRAFT_CREATED","performance_review",id);
            return Map.of("id",id,"status","DRAFT");
        });
    }
    @PostMapping("/reviews/{reviewId}/submit")
    public void submitReview(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID reviewId,
                             @Valid @RequestBody ManagerReviewInput input) {
        authenticated(user);
        tenants.inTenant(user.tenantId(),()->{
            int count=jdbc.update("""
                UPDATE performance_review SET rating=?,strengths=?,improvements=?,manager_comments=?,
                  status='SUBMITTED',submitted_at=now(),updated_at=now()
                WHERE tenant_id=? AND id=? AND reviewer_id=? AND status='DRAFT'
                  AND EXISTS(SELECT 1 FROM performance_cycle c
                    WHERE c.tenant_id=performance_review.tenant_id
                      AND c.id=performance_review.cycle_id AND c.status='ACTIVE')
                """,input.rating(),input.strengths(),input.improvements(),input.comments(),
                user.tenantId(),reviewId,user.id());
            if(count!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Only assigned reviewers can submit active drafts");
            audit(user,"PERFORMANCE_REVIEW_SUBMITTED","performance_review",reviewId);
            return null;
        });
    }
    @PostMapping("/reviews/{reviewId}/self-assessment")
    public void selfAssessment(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID reviewId,
             @Valid @RequestBody SelfAssessmentInput input) {
        authenticated(user);
        tenants.inTenant(user.tenantId(),()->{
            UUID linked=employee(user);
            if(linked==null) throw denied();
            int count=jdbc.update("""
                UPDATE performance_review r SET self_rating=?,self_assessment=?,updated_at=now()
                WHERE r.tenant_id=? AND r.id=? AND r.employee_id=? AND r.status='DRAFT'
                """,input.rating(),input.assessment().trim(),user.tenantId(),reviewId,linked);
            if(count!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Draft self-assessment unavailable");
            audit(user,"PERFORMANCE_SELF_ASSESSED","performance_review",reviewId);
            return null;
        });
    }
    @PostMapping("/reviews/{reviewId}/acknowledge")
    public void acknowledge(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID reviewId) {
        authenticated(user);
        tenants.inTenant(user.tenantId(),()->{
            UUID linked=employee(user);
            if(linked==null) throw denied();
            int count=jdbc.update("""
               UPDATE performance_review SET status='ACKNOWLEDGED',employee_acknowledged_at=now(),updated_at=now()
               WHERE tenant_id=? AND id=? AND employee_id=? AND status='SUBMITTED'
               """,user.tenantId(),reviewId,linked);
            if(count!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Submitted review unavailable");
            audit(user,"PERFORMANCE_REVIEW_ACKNOWLEDGED","performance_review",reviewId);
            return null;
        });
    }
    @PostMapping("/reviews/{reviewId}/finalize")
    public void finalizeReview(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID reviewId) {
        requireHr(user);
        tenants.inTenant(user.tenantId(),()->{
            int count=jdbc.update("""
               UPDATE performance_review SET status='FINALIZED',finalized_at=now(),finalized_by=?,updated_at=now()
               WHERE tenant_id=? AND id=? AND status='ACKNOWLEDGED'
               """,user.id(),user.tenantId(),reviewId);
            if(count!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Acknowledgment required before finalization");
            audit(user,"PERFORMANCE_REVIEW_FINALIZED","performance_review",reviewId);
            return null;
        });
    }
    @PostMapping("/plans/{planId}/status")
    public void planStatus(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID planId,
                           @Valid @RequestBody PlanStatusInput input) {
        authenticated(user);
        tenants.inTenant(user.tenantId(),()->{
            List<Map<String,Object>> rows=jdbc.queryForList("""
              SELECT r.employee_id,p.status FROM performance_development_plan p
              JOIN performance_review r ON r.tenant_id=p.tenant_id AND r.id=p.review_id
              WHERE p.tenant_id=? AND p.id=? FOR UPDATE OF p
              """,user.tenantId(),planId);
            if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            requireEmployeeAccess(user,(UUID)rows.get(0).get("employee_id"));
            String old=(String)rows.get(0).get("status"), next=input.status();
            if(!switch(old) {
                case "PLANNED" -> next.equals("IN_PROGRESS") || next.equals("CANCELLED");
                case "IN_PROGRESS" -> next.equals("COMPLETED") || next.equals("CANCELLED");
                default -> false;
            }) throw new ResponseStatusException(HttpStatus.CONFLICT,"Invalid plan transition");
            jdbc.update("UPDATE performance_development_plan SET status=?,updated_at=now() WHERE tenant_id=? AND id=?",
                next,user.tenantId(),planId);
            audit(user,"PERFORMANCE_PLAN_STATUS_UPDATED","performance_development_plan",planId);
            return null;
        });
    }
}
