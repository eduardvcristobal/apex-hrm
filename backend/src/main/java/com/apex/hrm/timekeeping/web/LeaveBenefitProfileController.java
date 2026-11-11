package com.apex.hrm.timekeeping.web;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

/** Effective-dated benefit packages separate leave eligibility from employment type. */
@RestController
@RequestMapping("/api/leave-benefit-profiles")
public class LeaveBenefitProfileController {
    public record ProfileInput(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{2,40}") String code,
                               @NotBlank @Size(max=120) String name,
                               @NotBlank @Size(max=40) String employeeClass,
                               @Size(max=1000) String description) {}
    public record EntitlementInput(@NotNull UUID leaveTypeId, @NotNull @DecimalMin("0.0") BigDecimal annualEntitlement,
                                   boolean paid, boolean hireProrationEnabled, boolean convertible,
                                   @DecimalMin("0.0") BigDecimal carryOverMax, @NotNull LocalDate effectiveFrom,
                                   LocalDate effectiveTo) {}
    public record AssignmentInput(@NotNull UUID employeeId, @NotNull UUID profileId, @NotNull LocalDate effectiveFrom,
                                  @Size(max=1000) String reason) {}

    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;

    public LeaveBenefitProfileController(JdbcTemplate jdbc, TenantJdbcExecutor tenants) { this.jdbc=jdbc; this.tenants=tenants; }

    @GetMapping
    public List<Map<String,Object>> list(@AuthenticationPrincipal HrmUserPrincipal user) {
        return tenants.inTenant(user.tenantId(),()->jdbc.queryForList("SELECT id,code,name,employee_class,description,active,created_at FROM leave_benefit_profile WHERE tenant_id=? ORDER BY name",user.tenantId()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody ProfileInput in) {
        return tenants.inTenant(user.tenantId(),()->{Map<String,Object> row=jdbc.queryForMap("INSERT INTO leave_benefit_profile(tenant_id,code,name,employee_class,description) VALUES(?,?,?,?,?) RETURNING id,code,name,employee_class,description,active,created_at",user.tenantId(),in.code().trim().toUpperCase(),in.name().trim(),in.employeeClass().trim().toUpperCase(),in.description()==null?null:in.description().trim());jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,'LEAVE_BENEFIT_PROFILE_CREATED','leave_benefit_profile',?)",user.tenantId(),user.id(),row.get("id"));return row;});
    }

    @GetMapping("/{profileId}/entitlements")
    public List<Map<String,Object>> entitlements(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID profileId) {
        return tenants.inTenant(user.tenantId(),()->{
            requireProfile(user.tenantId(),profileId);
            return jdbc.queryForList("SELECT e.id,e.leave_type_id,t.code AS leave_code,t.name AS leave_name,e.annual_entitlement,e.paid,e.hire_proration_enabled,e.convertible,e.carry_over_max,e.effective_from,e.effective_to FROM leave_benefit_entitlement e JOIN leave_type t ON t.tenant_id=e.tenant_id AND t.id=e.leave_type_id WHERE e.tenant_id=? AND e.profile_id=? ORDER BY t.name,e.effective_from DESC",user.tenantId(),profileId);
        });
    }

    @PutMapping("/{profileId}/entitlements")
    public Map<String,Object> saveEntitlement(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID profileId,@Valid @RequestBody EntitlementInput in) {
        return tenants.inTenant(user.tenantId(),()->{
            requireProfile(user.tenantId(),profileId);
            if(in.effectiveTo()!=null && in.effectiveTo().isBefore(in.effectiveFrom())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Effective-to must be on or after effective-from");
            Integer historicalRule=jdbc.queryForObject("SELECT count(*) FROM leave_benefit_entitlement WHERE tenant_id=? AND profile_id=? AND leave_type_id=? AND effective_from=?",Integer.class,user.tenantId(),profileId,in.leaveTypeId(),in.effectiveFrom());
            if(historicalRule!=null&&historicalRule>0&&!in.effectiveFrom().isAfter(LocalDate.now(ZoneId.of("Asia/Manila")))) throw new ResponseStatusException(HttpStatus.CONFLICT,"An effective entitlement cannot be edited after it takes effect; add a new future-dated rule instead");
            Integer overlaps=jdbc.queryForObject("SELECT count(*) FROM leave_benefit_entitlement WHERE tenant_id=? AND profile_id=? AND leave_type_id=? AND effective_from<>? AND (effective_to IS NULL OR effective_to>=?) AND (?::date IS NULL OR effective_from<=?::date)",Integer.class,user.tenantId(),profileId,in.leaveTypeId(),in.effectiveFrom(),in.effectiveFrom(),in.effectiveTo(),in.effectiveTo());
            if(overlaps!=null&&overlaps>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"This leave type already has an overlapping effective rule in the selected profile");
            Map<String,Object> row=jdbc.queryForMap("INSERT INTO leave_benefit_entitlement(tenant_id,profile_id,leave_type_id,annual_entitlement,paid,hire_proration_enabled,convertible,carry_over_max,effective_from,effective_to) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(tenant_id,profile_id,leave_type_id,effective_from) DO UPDATE SET annual_entitlement=excluded.annual_entitlement,paid=excluded.paid,hire_proration_enabled=excluded.hire_proration_enabled,convertible=excluded.convertible,carry_over_max=excluded.carry_over_max,effective_to=excluded.effective_to,updated_at=now() RETURNING id,leave_type_id,annual_entitlement,paid,hire_proration_enabled,convertible,carry_over_max,effective_from,effective_to",user.tenantId(),profileId,in.leaveTypeId(),in.annualEntitlement(),in.paid(),in.hireProrationEnabled(),in.convertible(),in.carryOverMax(),in.effectiveFrom(),in.effectiveTo());
            jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,'LEAVE_BENEFIT_ENTITLEMENT_SAVED','leave_benefit_entitlement',?)",user.tenantId(),user.id(),row.get("id"));
            return row;
        });
    }

    @GetMapping("/assignments")
    public List<Map<String,Object>> assignments(@AuthenticationPrincipal HrmUserPrincipal user) {
        return tenants.inTenant(user.tenantId(),()->jdbc.queryForList("SELECT a.id,a.employee_id,e.employee_no,e.first_name,e.last_name,a.profile_id,p.code AS profile_code,p.name AS profile_name,p.employee_class,a.effective_from,a.effective_to,a.reason,a.created_at FROM employee_leave_benefit_assignment a JOIN employee e ON e.tenant_id=a.tenant_id AND e.id=a.employee_id JOIN leave_benefit_profile p ON p.tenant_id=a.tenant_id AND p.id=a.profile_id WHERE a.tenant_id=? ORDER BY a.effective_from DESC,a.created_at DESC",user.tenantId()));
    }

    @PostMapping("/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String,Object> assign(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody AssignmentInput in) {
        return tenants.inTenant(user.tenantId(),()->{
            Integer employee=jdbc.queryForObject("SELECT count(*) FROM employee WHERE tenant_id=? AND id=?",Integer.class,user.tenantId(),in.employeeId());
            if(employee==null||employee==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
            requireProfile(user.tenantId(),in.profileId());
            Integer rules=jdbc.queryForObject("SELECT count(*) FROM leave_benefit_entitlement WHERE tenant_id=? AND profile_id=? AND effective_from<=? AND (effective_to IS NULL OR effective_to>=?)",Integer.class,user.tenantId(),in.profileId(),in.effectiveFrom(),in.effectiveFrom());
            if(rules==null||rules==0) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Add at least one effective leave entitlement before assigning this profile");
            Integer future=jdbc.queryForObject("SELECT count(*) FROM employee_leave_benefit_assignment WHERE tenant_id=? AND employee_id=? AND effective_from>=?",Integer.class,user.tenantId(),in.employeeId(),in.effectiveFrom());
            if(future!=null&&future>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"A benefit assignment already starts on or after this date; correct that assignment first");
            List<UUID> previousProfiles=jdbc.query("SELECT profile_id FROM employee_leave_benefit_assignment WHERE tenant_id=? AND employee_id=? AND effective_from<? AND (effective_to IS NULL OR effective_to>=?) ORDER BY effective_from DESC LIMIT 1",(rs,n)->rs.getObject(1,UUID.class),user.tenantId(),in.employeeId(),in.effectiveFrom(),in.effectiveFrom());
            jdbc.update("UPDATE employee_leave_benefit_assignment SET effective_to=?::date-1,updated_at=now() WHERE tenant_id=? AND employee_id=? AND effective_from<? AND (effective_to IS NULL OR effective_to>=?)",in.effectiveFrom(),user.tenantId(),in.employeeId(),in.effectiveFrom(),in.effectiveFrom());
            UUID id=jdbc.queryForObject("INSERT INTO employee_leave_benefit_assignment(tenant_id,employee_id,profile_id,effective_from,assigned_by,reason) VALUES(?,?,?,?,?,?) RETURNING id",UUID.class,user.tenantId(),in.employeeId(),in.profileId(),in.effectiveFrom(),user.id(),in.reason()==null?null:in.reason().trim());
            if(!previousProfiles.isEmpty()) {
                UUID previousProfile=previousProfiles.get(0);
                jdbc.update("INSERT INTO leave_ledger(tenant_id,employee_id,leave_type_id,entry_date,entry_type,days,reference_type,reference_id,remarks) SELECT ?,?,e.leave_type_id,?,'ADJUSTMENT',-CASE WHEN e.hire_proration_enabled THEN round(e.annual_entitlement*((make_date(extract(year from ?::date)::int,12,31)-?::date+1)::numeric/extract(doy from make_date(extract(year from ?::date)::int,12,31))::numeric),4) ELSE 0 END,'BENEFIT_PROFILE_ADJUSTMENT',?,p.name||' unearned benefit after profile change' FROM leave_benefit_entitlement e JOIN leave_benefit_profile p ON p.tenant_id=e.tenant_id AND p.id=e.profile_id WHERE e.tenant_id=? AND e.profile_id=? AND e.effective_from<=? AND (e.effective_to IS NULL OR e.effective_to>=?) AND e.annual_entitlement>0",user.tenantId(),in.employeeId(),in.effectiveFrom(),in.effectiveFrom(),in.effectiveFrom(),in.effectiveFrom(),id,user.tenantId(),previousProfile,in.effectiveFrom().minusDays(1),in.effectiveFrom().minusDays(1));
            } else {
                jdbc.update("INSERT INTO leave_ledger(tenant_id,employee_id,leave_type_id,entry_date,entry_type,days,reference_type,reference_id,remarks) SELECT ?,?,t.id,?,'ADJUSTMENT',-round(t.annual_entitlement*((make_date(extract(year from ?::date)::int,12,31)-?::date+1)::numeric/extract(doy from make_date(extract(year from ?::date)::int,12,31))::numeric),4),'BENEFIT_PROFILE_ADJUSTMENT',?,'Default entitlement replaced by assigned benefit profile' FROM leave_type t WHERE t.tenant_id=? AND t.hire_proration_enabled AND t.annual_entitlement>0 AND EXISTS(SELECT 1 FROM leave_ledger l WHERE l.tenant_id=t.tenant_id AND l.employee_id=? AND l.leave_type_id=t.id AND l.reference_type='HIRE_PRORATION')",user.tenantId(),in.employeeId(),in.effectiveFrom(),in.effectiveFrom(),in.effectiveFrom(),in.effectiveFrom(),id,user.tenantId(),in.employeeId());
            }
            jdbc.update("INSERT INTO leave_ledger(tenant_id,employee_id,leave_type_id,entry_date,entry_type,days,reference_type,reference_id,remarks) SELECT ?,?,e.leave_type_id,?,'ACCRUAL',CASE WHEN e.hire_proration_enabled THEN round(e.annual_entitlement*((make_date(extract(year from ?::date)::int,12,31)-?::date+1)::numeric/extract(doy from make_date(extract(year from ?::date)::int,12,31))::numeric),4) ELSE e.annual_entitlement END,'BENEFIT_PROFILE_ASSIGNMENT',?,p.name||' benefit-plan proration' FROM leave_benefit_entitlement e JOIN leave_benefit_profile p ON p.tenant_id=e.tenant_id AND p.id=e.profile_id WHERE e.tenant_id=? AND e.profile_id=? AND e.effective_from<=? AND (e.effective_to IS NULL OR e.effective_to>=?) AND e.annual_entitlement>0 ON CONFLICT (tenant_id,reference_id,leave_type_id) WHERE reference_type='BENEFIT_PROFILE_ASSIGNMENT' DO NOTHING",user.tenantId(),in.employeeId(),in.effectiveFrom(),in.effectiveFrom(),in.effectiveFrom(),in.effectiveFrom(),id,user.tenantId(),in.profileId(),in.effectiveFrom(),in.effectiveFrom());
            jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,'LEAVE_BENEFIT_PROFILE_ASSIGNED','employee_leave_benefit_assignment',?)",user.tenantId(),user.id(),id);
            return jdbc.queryForMap("SELECT a.id,a.employee_id,a.profile_id,p.code AS profile_code,p.name AS profile_name,p.employee_class,a.effective_from,a.effective_to,a.reason FROM employee_leave_benefit_assignment a JOIN leave_benefit_profile p ON p.tenant_id=a.tenant_id AND p.id=a.profile_id WHERE a.tenant_id=? AND a.id=?",user.tenantId(),id);
        });
    }

    private void requireProfile(UUID tenant,UUID id) {
        Integer exists=jdbc.queryForObject("SELECT count(*) FROM leave_benefit_profile WHERE tenant_id=? AND id=? AND active",Integer.class,tenant,id);
        if(exists==null||exists==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Active leave benefit profile not found");
    }
}
