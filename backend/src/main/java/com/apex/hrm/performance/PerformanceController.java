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

/** M5 performance data; every read/write executes inside tenant RLS and requires HR + MFA. */
@RestController
@RequestMapping("/api/v1/performance")
public class PerformanceController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenant;
    public PerformanceController(JdbcTemplate jdbc,TenantJdbcExecutor tenant) {
        this.jdbc=jdbc; this.tenant=tenant;
    }
    public record CycleInput(@NotNull UUID companyId,@NotBlank @Size(max=160) String name,
                             @NotNull LocalDate startsOn,@NotNull LocalDate endsOn) {}
    public record GoalInput(@NotNull UUID cycleId,@NotNull UUID employeeId,
         @NotBlank @Size(max=200) String title,@Size(max=4000) String description,
         @Size(max=160) String metric,@DecimalMin("0.0") BigDecimal targetValue,
         @DecimalMin("0.0") BigDecimal actualValue,
         @NotNull @DecimalMin("0.01") @DecimalMax("100.0") BigDecimal weight,LocalDate dueOn) {}
    public record ReviewInput(@NotNull UUID cycleId,@NotNull UUID employeeId,
          @NotNull @DecimalMin("1.0") @DecimalMax("5.0") BigDecimal rating,
          @Size(max=5000) String strengths,@Size(max=5000) String improvements,
          @Size(max=5000) String managerComments) {}
    public record PlanInput(@NotBlank @Size(max=200) String title,
          @NotBlank @Size(max=10000) String actionItems,LocalDate targetDate) {}

    private void authorize(HrmUserPrincipal user) {
        if(user==null || !user.isEnabled() || !user.getAuthorities().stream()
             .anyMatch(a->a.getAuthority().equals("ROLE_MFA_VERIFIED"))
             || user.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_TENANT_ADMIN")
                || a.getAuthority().equals("ROLE_HR_ADMIN"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"HR administrator with MFA required");
        }
    }
    private void require(boolean valid,String message) {
        if(!valid) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    private boolean exists(String sql,Object... params) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,params));
    }
    private void audit(HrmUserPrincipal user,String action,String kind,UUID id) {
        jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,?,?,?)",
            user.tenantId(),user.id(),action,kind,id);
    }
    @GetMapping("/cycles")
    public List<Map<String,Object>> cycles(@AuthenticationPrincipal HrmUserPrincipal user,
                                            @RequestParam UUID companyId) {
        authorize(user);
        return tenant.inTenant(user.tenantId(),()->jdbc.queryForList("""
            SELECT id,company_id AS "companyId",name,starts_on AS "startsOn",
                ends_on AS "endsOn",status FROM performance_cycle
            WHERE tenant_id=? AND company_id=? ORDER BY starts_on DESC LIMIT 100
            """,user.tenantId(),companyId));
    }
    @PostMapping("/cycles")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createCycle(@AuthenticationPrincipal HrmUserPrincipal user,
                                           @Valid @RequestBody CycleInput input) {
        authorize(user);
        require(!input.endsOn().isBefore(input.startsOn()),"Cycle end precedes start");
        return tenant.inTenant(user.tenantId(),()->{
            require(exists("SELECT EXISTS(SELECT 1 FROM company WHERE tenant_id=? AND id=?)",
                user.tenantId(),input.companyId()),"Company does not exist");
            UUID id=jdbc.queryForObject("""
                INSERT INTO performance_cycle(tenant_id,company_id,name,starts_on,ends_on,created_by)
                VALUES(?,?,?,?,?,?) RETURNING id
                """,UUID.class,user.tenantId(),input.companyId(),input.name().trim(),
                input.startsOn(),input.endsOn(),user.id());
            audit(user,"PERFORMANCE_CYCLE_CREATED","performance_cycle",id);
            return Map.of("id",id,"status","DRAFT");
        });
    }
    @PostMapping("/cycles/{id}/activate")
    public void activate(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID id) {
        authorize(user);
        tenant.inTenant(user.tenantId(),()->{
            int count=jdbc.update("""
                UPDATE performance_cycle SET status='ACTIVE'
                WHERE tenant_id=? AND id=? AND status='DRAFT'
                """,user.tenantId(),id);
            if(count!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Cycle must be a draft");
            audit(user,"PERFORMANCE_CYCLE_ACTIVATED","performance_cycle",id);
            return null;
        });
    }
    @GetMapping("/goals")
    public List<Map<String,Object>> goals(@AuthenticationPrincipal HrmUserPrincipal user,
          @RequestParam UUID cycleId) {
        authorize(user);
        return tenant.inTenant(user.tenantId(),()->jdbc.queryForList("""
            SELECT id,cycle_id AS "cycleId",employee_id AS "employeeId",title,description,
                metric,target_value AS "targetValue",actual_value AS "actualValue",weight,status,
                due_on AS "dueOn" FROM performance_goal
            WHERE tenant_id=? AND cycle_id=? ORDER BY created_at DESC LIMIT 500
            """,user.tenantId(),cycleId));
    }
    @PostMapping("/goals")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createGoal(@AuthenticationPrincipal HrmUserPrincipal user,
           @Valid @RequestBody GoalInput input) {
        authorize(user);
        return tenant.inTenant(user.tenantId(),()->{
            require(exists("""
              SELECT EXISTS(SELECT 1 FROM performance_cycle c JOIN personnel_employee e
                 ON e.tenant_id=c.tenant_id AND e.company_id=c.company_id
              WHERE c.tenant_id=? AND c.id=? AND e.id=? AND c.status='ACTIVE')
              """,user.tenantId(),input.cycleId(),input.employeeId()),"Active cycle and employee must share a company");
            UUID id=jdbc.queryForObject("""
                INSERT INTO performance_goal(tenant_id,cycle_id,employee_id,title,description,
                   metric,target_value,actual_value,weight,due_on,created_by,status)
                VALUES(?,?,?,?,?,?,?,?,?,? ,?,'ACTIVE') RETURNING id
                """,UUID.class,user.tenantId(),input.cycleId(),input.employeeId(),input.title().trim(),
                  input.description(),input.metric(),input.targetValue(),input.actualValue(),
                  input.weight(),input.dueOn(),user.id());
            audit(user,"PERFORMANCE_GOAL_CREATED","performance_goal",id);
            return Map.of("id",id,"status","ACTIVE");
        });
    }
    @GetMapping("/reviews")
    public List<Map<String,Object>> reviews(@AuthenticationPrincipal HrmUserPrincipal user,
          @RequestParam UUID cycleId) {
        authorize(user);
        return tenant.inTenant(user.tenantId(),()->jdbc.queryForList("""
            SELECT id,cycle_id AS "cycleId",employee_id AS "employeeId",
              reviewer_id AS "reviewerId",rating,strengths,improvements,
              manager_comments AS "managerComments",status,submitted_at AS "submittedAt"
            FROM performance_review WHERE tenant_id=? AND cycle_id=?
            ORDER BY created_at DESC LIMIT 500
            """,user.tenantId(),cycleId));
    }
    @PostMapping("/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createReview(@AuthenticationPrincipal HrmUserPrincipal user,
           @Valid @RequestBody ReviewInput input) {
        authorize(user);
        return tenant.inTenant(user.tenantId(),()->{
            require(exists("""
              SELECT EXISTS(SELECT 1 FROM performance_cycle c JOIN personnel_employee e
                ON e.tenant_id=c.tenant_id AND e.company_id=c.company_id
              WHERE c.tenant_id=? AND c.id=? AND e.id=? AND c.status='ACTIVE')
              """,user.tenantId(),input.cycleId(),input.employeeId()),"Active cycle and employee must share a company");
            UUID id=jdbc.queryForObject("""
                INSERT INTO performance_review(tenant_id,cycle_id,employee_id,reviewer_id,
                    rating,strengths,improvements,manager_comments,status,submitted_at)
                VALUES(?,?,?,?,?,?,?,?,'SUBMITTED',now()) RETURNING id
                """,UUID.class,user.tenantId(),input.cycleId(),input.employeeId(),user.id(),
                    input.rating(),input.strengths(),input.improvements(),input.managerComments());
            audit(user,"PERFORMANCE_REVIEW_SUBMITTED","performance_review",id);
            return Map.of("id",id,"status","SUBMITTED");
        });
    }
    @GetMapping("/reviews/{reviewId}/plans")
    public List<Map<String,Object>> plans(@AuthenticationPrincipal HrmUserPrincipal user,
                                           @PathVariable UUID reviewId) {
        authorize(user);
        return tenant.inTenant(user.tenantId(),()->jdbc.queryForList("""
            SELECT id,review_id AS "reviewId",title,action_items AS "actionItems",
              target_date AS "targetDate",status FROM performance_development_plan
            WHERE tenant_id=? AND review_id=? ORDER BY created_at DESC LIMIT 100
            """,user.tenantId(),reviewId));
    }
    @PostMapping("/reviews/{reviewId}/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createPlan(@AuthenticationPrincipal HrmUserPrincipal user,
          @PathVariable UUID reviewId,@Valid @RequestBody PlanInput input) {
        authorize(user);
        return tenant.inTenant(user.tenantId(),()->{
            require(exists("SELECT EXISTS(SELECT 1 FROM performance_review WHERE tenant_id=? AND id=? AND status='SUBMITTED')",
               user.tenantId(),reviewId),"Review must be submitted");
            UUID id=jdbc.queryForObject("""
                INSERT INTO performance_development_plan(tenant_id,review_id,title,action_items,target_date)
                VALUES(?,?,?,?,?) RETURNING id
                """,UUID.class,user.tenantId(),reviewId,input.title().trim(),input.actionItems().trim(),input.targetDate());
            audit(user,"PERFORMANCE_PLAN_CREATED","performance_development_plan",id);
            return Map.of("id",id,"status","PLANNED");
        });
    }
}
