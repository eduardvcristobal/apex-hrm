package com.apex.hrm.performance;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import com.apex.hrm.approvals.ApprovalWorkflowService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.HashSet;
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
    private final ApprovalWorkflowService approvals;
    public PerformanceWorkflowController(JdbcTemplate jdbc,TenantJdbcExecutor tenants,ApprovalWorkflowService approvals) {
        this.jdbc=jdbc; this.tenants=tenants; this.approvals=approvals;
    }
    public record LinkInput(@NotNull UUID employeeId,@NotNull UUID identityId) {}
    public record ProgressInput(@NotNull @DecimalMin("0") BigDecimal actualValue,
                                @Size(max=2000) String note) {}
    public record SelfAssessmentInput(@NotNull @DecimalMin("1") @DecimalMax("5") BigDecimal rating,
                                      @NotBlank @Size(max=10000) String assessment) {}
    public record PlanStatusInput(@NotBlank @Pattern(regexp="PLANNED|IN_PROGRESS|COMPLETED|CANCELLED") String status) {}
    public record ReviewerInput(@NotNull @Size(max=10) List<@NotNull UUID> identityIds) {}
    public record ApprovalInput(@Size(max=2000) String note) {}
    public record CalibrationInput(@NotNull @DecimalMin("1") @DecimalMax("5") BigDecimal rating,
                                   @NotBlank @Size(max=4000) String reason) {}
    public record AppealInput(@NotBlank @Size(max=10000) String reason) {}
    public record AppealDecisionInput(@NotBlank @Pattern(regexp="UPHELD|ADJUSTED|REJECTED") String decision,
                                      @NotBlank @Size(max=4000) String resolution,
                                      @DecimalMin("1") @DecimalMax("5") BigDecimal adjustedRating) {}
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
    @GetMapping("/my/reviews")
    public List<Map<String,Object>> myReviews(@AuthenticationPrincipal HrmUserPrincipal user) {
        authenticated(user);
        return tenants.inTenant(user.tenantId(),()->{
            UUID id=employee(user);
            if(id==null) throw denied();
            return jdbc.queryForList("""
               SELECT id,cycle_id AS "cycleId",rating,final_rating AS "finalRating",self_rating AS "selfRating",
                self_assessment AS "selfAssessment",manager_comments AS "managerComments",
                status,employee_acknowledged_at AS "acknowledgedAt",
                (SELECT count(*) FROM performance_review_approval a WHERE a.tenant_id=performance_review.tenant_id AND a.review_id=performance_review.id) AS "approvalCount",
                (SELECT count(*) FROM performance_review_approval a WHERE a.tenant_id=performance_review.tenant_id AND a.review_id=performance_review.id AND a.status='APPROVED') AS "approvedCount"
               FROM performance_review WHERE tenant_id=? AND employee_id=?
                 AND status IN ('DRAFT','SUBMITTED','ACKNOWLEDGED','FINALIZED')
               ORDER BY created_at DESC LIMIT 100
               """,user.tenantId(),id);
        });
    }
    @GetMapping("/my/plans")
    public List<Map<String,Object>> myPlans(@AuthenticationPrincipal HrmUserPrincipal user) {
        authenticated(user);
        return tenants.inTenant(user.tenantId(),()->{
            UUID id=employee(user);
            if(id==null) throw denied();
            return jdbc.queryForList("""
                SELECT p.id,p.review_id AS "reviewId",p.title,p.action_items AS "actionItems",
                       p.target_date AS "targetDate",p.status
                FROM performance_development_plan p JOIN performance_review r
                   ON r.tenant_id=p.tenant_id AND r.id=p.review_id
                WHERE p.tenant_id=? AND r.employee_id=?
                ORDER BY p.created_at DESC LIMIT 100
                """,user.tenantId(),id);
        });
    }
    @GetMapping("/team/assignments")
    public List<Map<String,Object>> teamAssignments(@AuthenticationPrincipal HrmUserPrincipal user) {
        authenticated(user);
        if(!hr(user)&&!has(user,"ROLE_MANAGER")) throw denied();
        return tenants.inTenant(user.tenantId(),()->{
            UUID manager=employee(user);
            if(!hr(user)&&manager==null) throw denied();
            if(hr(user)) return jdbc.queryForList("""
                SELECT e.id AS "employeeId",e.first_name AS "firstName",e.last_name AS "lastName",
                       c.id AS "cycleId",c.name AS "cycleName"
                FROM personnel_employee e JOIN performance_cycle c
                  ON c.tenant_id=e.tenant_id AND c.company_id=e.company_id
                WHERE e.tenant_id=? AND e.employment_status='ACTIVE' AND c.status='ACTIVE'
                ORDER BY e.last_name,e.first_name,c.starts_on DESC LIMIT 500
                """,user.tenantId());
            return jdbc.queryForList("""
                SELECT e.id AS "employeeId",e.first_name AS "firstName",e.last_name AS "lastName",
                       c.id AS "cycleId",c.name AS "cycleName"
                FROM personnel_employee e JOIN performance_cycle c
                  ON c.tenant_id=e.tenant_id AND c.company_id=e.company_id
                WHERE e.tenant_id=? AND e.supervisor_id=? AND e.employment_status='ACTIVE'
                  AND c.status='ACTIVE'
                ORDER BY e.last_name,e.first_name,c.starts_on DESC LIMIT 500
                """,user.tenantId(),manager);
        });
    }
    @GetMapping("/team/reviews")
    public List<Map<String,Object>> teamReviews(@AuthenticationPrincipal HrmUserPrincipal user) {
        authenticated(user);
        if(!hr(user)&&!has(user,"ROLE_MANAGER")) throw denied();
        return tenants.inTenant(user.tenantId(),()->{
            UUID manager=employee(user);
            if(!hr(user)&&manager==null) throw denied();
            if(hr(user)) return jdbc.queryForList("""
                SELECT r.id,r.employee_id AS "employeeId",r.reviewer_id AS "reviewerId",r.rating,r.status,
                  cr.id AS "recommendationId",cr.current_amount AS "currentAmount",cr.proposed_amount AS "proposedAmount",cr.pay_basis AS "payBasis",cr.effective_date AS "effectiveDate",cr.status AS "recommendationStatus"
                FROM performance_review r LEFT JOIN performance_compensation_recommendation cr ON cr.tenant_id=r.tenant_id AND cr.review_id=r.id
                WHERE r.tenant_id=? ORDER BY r.created_at DESC LIMIT 500
                """,user.tenantId());
            return jdbc.queryForList("""
                SELECT r.id,r.employee_id AS "employeeId",r.reviewer_id AS "reviewerId",r.rating,r.status,
                  cr.id AS "recommendationId",cr.current_amount AS "currentAmount",cr.proposed_amount AS "proposedAmount",cr.pay_basis AS "payBasis",cr.effective_date AS "effectiveDate",cr.status AS "recommendationStatus",
                  (SELECT count(*) FROM performance_review_approval a WHERE a.tenant_id=r.tenant_id AND a.review_id=r.id) AS "approvalCount",
                  (SELECT count(*) FROM performance_review_approval a WHERE a.tenant_id=r.tenant_id AND a.review_id=r.id AND a.status='APPROVED') AS "approvedCount"
                FROM performance_review r JOIN personnel_employee e
                ON e.tenant_id=r.tenant_id AND e.id=r.employee_id
                LEFT JOIN performance_compensation_recommendation cr ON cr.tenant_id=r.tenant_id AND cr.review_id=r.id
                WHERE r.tenant_id=? AND e.supervisor_id=? AND r.reviewer_id=?
                ORDER BY r.created_at DESC LIMIT 500
                """,user.tenantId(),manager,user.id());
        });
    }
    @PostMapping("/reviews/{reviewId}/compensation-recommendations")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> recommendCompensation(@AuthenticationPrincipal HrmUserPrincipal user,
            @PathVariable UUID reviewId,@Valid @RequestBody CompensationRecommendationInput input) {
        authenticated(user);
        if(!has(user,"ROLE_MANAGER")&&!hr(user)) throw denied();
        return tenants.inTenant(user.tenantId(),()->{
            UUID manager=employee(user);
            List<Map<String,Object>> rows=jdbc.queryForList("""
                SELECT r.employee_id,e.supervisor_id FROM performance_review r
                JOIN personnel_employee e ON e.tenant_id=r.tenant_id AND e.id=r.employee_id
                WHERE r.tenant_id=? AND r.id=? AND r.status='FINALIZED' AND r.reviewer_id=?
                """,user.tenantId(),reviewId,user.id());
            if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Finalize the appraisal before recommending a compensation change");
            UUID target=(UUID)rows.get(0).get("employee_id");
            if(!hr(user)&&(manager==null||!manager.equals(rows.get(0).get("supervisor_id")))) throw denied();
            List<Map<String,Object>> compensation=jdbc.queryForList("""
                SELECT amount,currency_code,pay_basis FROM personnel_compensation_history
                WHERE tenant_id=? AND employee_id=? AND effective_date<=? ORDER BY effective_date DESC LIMIT 1
                """,user.tenantId(),target,input.effectiveDate());
            if(compensation.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"The employee needs an effective compensation record before a raise can be recommended");
            BigDecimal current=(BigDecimal)compensation.get(0).get("amount");
            if(input.proposedAmount().compareTo(current)<=0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Proposed compensation must be greater than the current amount");
            String basis=(String)compensation.get(0).get("pay_basis");
            UUID id=jdbc.queryForObject("""
                INSERT INTO performance_compensation_recommendation(tenant_id,review_id,employee_id,current_amount,proposed_amount,currency_code,pay_basis,effective_date,reason,created_by)
                VALUES(?,?,?,?,?,?,?,?,?,?) RETURNING id
                """,UUID.class,user.tenantId(),reviewId,target,current,input.proposedAmount(),compensation.get(0).get("currency_code"),basis,input.effectiveDate(),input.reason().trim(),user.id());
            UUID approvalId=approvals.start(user.tenantId(),user.id(),manager==null?target:manager,"COMPENSATION_CHANGE","performance_compensation_recommendation",id,
                "Compensation increase recommendation: "+current+" to "+input.proposedAmount()+" "+basis+" effective "+input.effectiveDate()+". Reason: "+input.reason().trim());
            jdbc.update("UPDATE performance_compensation_recommendation SET approval_instance_id=? WHERE tenant_id=? AND id=?",approvalId,user.tenantId(),id);
            audit(user,"PERFORMANCE_COMPENSATION_RECOMMENDED","performance_compensation_recommendation",id);
            return Map.of("id",id,"approvalId",approvalId,"status","PENDING","currentAmount",current,"proposedAmount",input.proposedAmount(),"payBasis",basis);
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
    public record CompensationRecommendationInput(@NotNull @DecimalMin("0.01") BigDecimal proposedAmount,
                                     @NotNull java.time.LocalDate effectiveDate,
                                     @NotBlank @Size(max=4000) String reason) {}
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
                  AND (EXISTS(SELECT 1 FROM auth_identity_role role
                       WHERE role.identity_id=? AND role.role IN ('TENANT_ADMIN','HR_ADMIN'))
                    OR EXISTS(SELECT 1 FROM performance_employee_identity link
                       JOIN personnel_employee e
                         ON e.tenant_id=link.tenant_id AND e.id=performance_review.employee_id
                       WHERE link.tenant_id=performance_review.tenant_id AND link.identity_id=?
                         AND e.supervisor_id=link.employee_id))
                  AND EXISTS(SELECT 1 FROM performance_cycle c
                    WHERE c.tenant_id=performance_review.tenant_id
                      AND c.id=performance_review.cycle_id AND c.status='ACTIVE')
                """,input.rating(),input.strengths(),input.improvements(),input.comments(),
                user.tenantId(),reviewId,user.id(),user.id(),user.id());
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
                 AND NOT EXISTS(SELECT 1 FROM performance_review_approval a
                   WHERE a.tenant_id=performance_review.tenant_id AND a.review_id=performance_review.id
                     AND a.status<>'APPROVED')
               """,user.tenantId(),reviewId,linked);
            if(count!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Review is unavailable or assigned reviewers have not all approved");
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

    @PostMapping("/reviews/{reviewId}/approvers")
    public List<Map<String,Object>> assignApprovers(@AuthenticationPrincipal HrmUserPrincipal user,
            @PathVariable UUID reviewId,@Valid @RequestBody ReviewerInput input) {
        requireHr(user);
        if(new HashSet<>(input.identityIds()).size()!=input.identityIds().size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Reviewer accounts must be unique");
        return tenants.inTenant(user.tenantId(),()->{
            List<UUID> employeeIds=jdbc.query("SELECT employee_id FROM performance_review WHERE tenant_id=? AND id=? AND status='DRAFT' FOR UPDATE",
                (rs,i)->rs.getObject(1,UUID.class),user.tenantId(),reviewId);
            if(employeeIds.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Review must be a draft before reviewers are assigned");
            for(UUID identityId:input.identityIds()) {
                if(!exists("SELECT EXISTS(SELECT 1 FROM auth_identity i JOIN auth_identity_role r ON r.identity_id=i.id WHERE i.tenant_id=? AND i.id=? AND i.enabled AND r.role IN ('TENANT_ADMIN','HR_ADMIN','MANAGER'))",user.tenantId(),identityId)
                    || exists("SELECT EXISTS(SELECT 1 FROM performance_review WHERE tenant_id=? AND id=? AND reviewer_id=?)",user.tenantId(),reviewId,identityId)
                    || exists("SELECT EXISTS(SELECT 1 FROM performance_employee_identity WHERE tenant_id=? AND employee_id=? AND identity_id=?)",user.tenantId(),employeeIds.get(0),identityId))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Each approver must be an active HR administrator or manager distinct from the employee and manager reviewer");
            }
            jdbc.update("DELETE FROM performance_review_approval WHERE tenant_id=? AND review_id=?",user.tenantId(),reviewId);
            for(UUID identityId:input.identityIds()) jdbc.update("INSERT INTO performance_review_approval(tenant_id,review_id,approver_id,assigned_by) VALUES(?,?,?,?)",user.tenantId(),reviewId,identityId,user.id());
            audit(user,"PERFORMANCE_REVIEW_APPROVERS_ASSIGNED","performance_review",reviewId);
            return jdbc.queryForList("SELECT a.id AS \"approvalId\",a.approver_id AS \"approverId\",i.email AS \"approverEmail\",a.status FROM performance_review_approval a JOIN auth_identity i ON i.tenant_id=a.tenant_id AND i.id=a.approver_id WHERE a.tenant_id=? AND a.review_id=? ORDER BY i.email",user.tenantId(),reviewId);
        });
    }

    @GetMapping("/reviews/{reviewId}/approvers")
    public List<Map<String,Object>> approvers(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID reviewId) {
        requireHr(user);
        return tenants.inTenant(user.tenantId(),()->jdbc.queryForList("SELECT a.id AS \"approvalId\",a.approver_id AS \"approverId\",i.email AS \"approverEmail\",a.status,a.decision_note AS \"decisionNote\",a.assigned_at AS \"assignedAt\",a.decided_at AS \"decidedAt\" FROM performance_review_approval a JOIN auth_identity i ON i.tenant_id=a.tenant_id AND i.id=a.approver_id WHERE a.tenant_id=? AND a.review_id=? ORDER BY i.email",user.tenantId(),reviewId));
    }

    @GetMapping("/my/approvals")
    public List<Map<String,Object>> myApprovals(@AuthenticationPrincipal HrmUserPrincipal user) {
        authenticated(user);
        if(!hr(user)&&!has(user,"ROLE_MANAGER")) throw denied();
        return tenants.inTenant(user.tenantId(),()->jdbc.queryForList("""
            SELECT a.id AS "approvalId",a.review_id AS "reviewId",e.id AS "employeeId",
              e.first_name AS "firstName",e.last_name AS "lastName",c.name AS "cycleName",
              r.rating,r.status AS "reviewStatus",a.assigned_at AS "assignedAt"
            FROM performance_review_approval a JOIN performance_review r
              ON r.tenant_id=a.tenant_id AND r.id=a.review_id
            JOIN personnel_employee e ON e.tenant_id=r.tenant_id AND e.id=r.employee_id
            JOIN performance_cycle c ON c.tenant_id=r.tenant_id AND c.id=r.cycle_id
            WHERE a.tenant_id=? AND a.approver_id=? AND a.status='PENDING' AND r.status='SUBMITTED'
            ORDER BY a.assigned_at LIMIT 200
            """,user.tenantId(),user.id()));
    }

    @PostMapping("/reviews/{reviewId}/approvals/{approvalId}/approve")
    public void approveReview(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID reviewId,
            @PathVariable UUID approvalId,@Valid @RequestBody(required=false) ApprovalInput input) {
        authenticated(user);
        if(!hr(user)&&!has(user,"ROLE_MANAGER")) throw denied();
        tenants.inTenant(user.tenantId(),()->{
            int count=jdbc.update("""
                UPDATE performance_review_approval a SET status='APPROVED',decision_note=?,decided_at=now()
                FROM performance_review r
                WHERE a.tenant_id=? AND a.review_id=? AND a.id=? AND a.approver_id=? AND a.status='PENDING'
                  AND r.tenant_id=a.tenant_id AND r.id=a.review_id AND r.status='SUBMITTED'
                """,input==null?null:input.note(),user.tenantId(),reviewId,approvalId,user.id());
            if(count!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Approval is not assigned, pending, or awaiting manager submission");
            audit(user,"PERFORMANCE_REVIEW_APPROVED","performance_review",reviewId);
            return null;
        });
    }

    @PostMapping("/reviews/{reviewId}/calibration")
    public Map<String,Object> calibrate(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID reviewId,
            @Valid @RequestBody CalibrationInput input) {
        requireHr(user);
        return tenants.inTenant(user.tenantId(),()->{
            List<BigDecimal> ratings=jdbc.query("SELECT COALESCE(final_rating,rating) FROM performance_review WHERE tenant_id=? AND id=? AND status='ACKNOWLEDGED' FOR UPDATE",
                (rs,i)->rs.getBigDecimal(1),user.tenantId(),reviewId);
            if(ratings.isEmpty()||ratings.get(0)==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Only acknowledged reviews with a manager rating can be calibrated");
            BigDecimal previous=ratings.get(0);
            jdbc.update("UPDATE performance_review SET final_rating=?,updated_at=now() WHERE tenant_id=? AND id=?",input.rating(),user.tenantId(),reviewId);
            jdbc.update("INSERT INTO performance_review_calibration(tenant_id,review_id,previous_rating,calibrated_rating,reason,calibrated_by) VALUES(?,?,?,?,?,?)",user.tenantId(),reviewId,previous,input.rating(),input.reason().trim(),user.id());
            audit(user,"PERFORMANCE_REVIEW_CALIBRATED","performance_review",reviewId);
            return Map.of("reviewId",reviewId,"previousRating",previous,"finalRating",input.rating());
        });
    }

    @GetMapping("/reviews/{reviewId}/calibrations")
    public List<Map<String,Object>> calibrations(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID reviewId) {
        requireHr(user);
        return tenants.inTenant(user.tenantId(),()->jdbc.queryForList("SELECT c.id,c.previous_rating AS \"previousRating\",c.calibrated_rating AS \"calibratedRating\",c.reason,c.source,c.calibrated_by AS \"calibratedBy\",i.email AS \"calibratedByEmail\",c.calibrated_at AS \"calibratedAt\" FROM performance_review_calibration c JOIN auth_identity i ON i.tenant_id=c.tenant_id AND i.id=c.calibrated_by WHERE c.tenant_id=? AND c.review_id=? ORDER BY c.calibrated_at DESC",user.tenantId(),reviewId));
    }

    @GetMapping("/my/appeals")
    public List<Map<String,Object>> myAppeals(@AuthenticationPrincipal HrmUserPrincipal user) {
        authenticated(user);
        return tenants.inTenant(user.tenantId(),()->{ UUID linked=employee(user); if(linked==null) throw denied();
            return jdbc.queryForList("SELECT a.id,a.review_id AS \"reviewId\",a.reason,a.status,a.resolution,a.adjusted_rating AS \"adjustedRating\",a.created_at AS \"createdAt\",a.decided_at AS \"decidedAt\" FROM performance_review_appeal a WHERE a.tenant_id=? AND a.employee_id=? ORDER BY a.created_at DESC LIMIT 100",user.tenantId(),linked); });
    }

    @PostMapping("/reviews/{reviewId}/appeals")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> appeal(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID reviewId,
            @Valid @RequestBody AppealInput input) {
        authenticated(user);
        return tenants.inTenant(user.tenantId(),()->{
            UUID linked=employee(user); if(linked==null) throw denied();
            if(!exists("SELECT EXISTS(SELECT 1 FROM performance_review WHERE tenant_id=? AND id=? AND employee_id=? AND status='FINALIZED' AND finalized_at>=now()-interval '30 days')",user.tenantId(),reviewId,linked))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Only reviews finalized within the last 30 days can be appealed");
            UUID id=jdbc.queryForObject("INSERT INTO performance_review_appeal(tenant_id,review_id,employee_id,reason) VALUES(?,?,?,?) RETURNING id",UUID.class,user.tenantId(),reviewId,linked,input.reason().trim());
            audit(user,"PERFORMANCE_REVIEW_APPEALED","performance_review",reviewId);
            return Map.of("id",id,"reviewId",reviewId,"status","OPEN");
        });
    }

    @GetMapping("/appeals")
    public List<Map<String,Object>> appeals(@AuthenticationPrincipal HrmUserPrincipal user) {
        requireHr(user);
        return tenants.inTenant(user.tenantId(),()->jdbc.queryForList("SELECT a.id,a.review_id AS \"reviewId\",a.employee_id AS \"employeeId\",e.first_name AS \"firstName\",e.last_name AS \"lastName\",c.name AS \"cycleName\",a.reason,a.status,a.resolution,a.adjusted_rating AS \"adjustedRating\",COALESCE(r.final_rating,r.rating) AS rating,a.created_at AS \"createdAt\" FROM performance_review_appeal a JOIN personnel_employee e ON e.tenant_id=a.tenant_id AND e.id=a.employee_id JOIN performance_review r ON r.tenant_id=a.tenant_id AND r.id=a.review_id JOIN performance_cycle c ON c.tenant_id=r.tenant_id AND c.id=r.cycle_id WHERE a.tenant_id=? ORDER BY CASE a.status WHEN 'OPEN' THEN 0 ELSE 1 END,a.created_at DESC LIMIT 500",user.tenantId()));
    }

    @PostMapping("/appeals/{appealId}/decision")
    public Map<String,Object> decideAppeal(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID appealId,
            @Valid @RequestBody AppealDecisionInput input) {
        requireHr(user);
        if(input.decision().equals("ADJUSTED")!=(input.adjustedRating()!=null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Provide an adjusted rating only for an adjusted appeal outcome");
        return tenants.inTenant(user.tenantId(),()->{
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT a.review_id AS \"reviewId\",COALESCE(r.final_rating,r.rating) AS rating FROM performance_review_appeal a JOIN performance_review r ON r.tenant_id=a.tenant_id AND r.id=a.review_id WHERE a.tenant_id=? AND a.id=? AND a.status='OPEN' FOR UPDATE OF a,r",user.tenantId(),appealId);
            if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Open appeal not found");
            UUID reviewId=(UUID)rows.get(0).get("reviewId"); BigDecimal previous=(BigDecimal)rows.get(0).get("rating");
            if(input.decision().equals("ADJUSTED") && previous==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"A review without a rating cannot be adjusted");
            jdbc.update("UPDATE performance_review_appeal SET status=?,resolution=?,adjusted_rating=?,decided_by=?,decided_at=now() WHERE tenant_id=? AND id=?",input.decision(),input.resolution().trim(),input.adjustedRating(),user.id(),user.tenantId(),appealId);
            if(input.decision().equals("ADJUSTED")) {
                jdbc.update("UPDATE performance_review SET final_rating=?,updated_at=now() WHERE tenant_id=? AND id=?",input.adjustedRating(),user.tenantId(),reviewId);
                jdbc.update("INSERT INTO performance_review_calibration(tenant_id,review_id,previous_rating,calibrated_rating,reason,source,calibrated_by) VALUES(?,?,?,?,?,'APPEAL',?)",user.tenantId(),reviewId,previous,input.adjustedRating(),input.resolution().trim(),user.id());
            }
            audit(user,"PERFORMANCE_REVIEW_APPEAL_DECIDED","performance_review_appeal",appealId);
            return Map.of("id",appealId,"reviewId",reviewId,"status",input.decision());
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
            if("COMPLETED".equals(next)) {
                Integer incomplete=jdbc.queryForObject("""
                    SELECT COUNT(*) FROM performance_plan_milestone
                    WHERE tenant_id=? AND plan_id=? AND status NOT IN ('COMPLETED','CANCELLED')
                    """,Integer.class,user.tenantId(),planId);
                Integer completed=jdbc.queryForObject("""
                    SELECT COUNT(*) FROM performance_plan_milestone
                    WHERE tenant_id=? AND plan_id=? AND status='COMPLETED'
                    """,Integer.class,user.tenantId(),planId);
                if(incomplete==null || incomplete>0 || completed==null || completed==0)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Complete at least one milestone and resolve remaining milestones before closing the plan");
            }
            jdbc.update("UPDATE performance_development_plan SET status=?,updated_at=now() WHERE tenant_id=? AND id=?",
                next,user.tenantId(),planId);
            audit(user,"PERFORMANCE_PLAN_STATUS_UPDATED","performance_development_plan",planId);
            return null;
        });
    }
}
