package com.apex.hrm.personnel;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Preview and apply minimum-rate salary movements without losing payroll's effective-dated rate segments. */
@RestController
@RequestMapping("/api/v1/personnel/compensation-movements")
public class CompensationMovementController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public CompensationMovementController(JdbcTemplate jdbc,TenantJdbcExecutor tenants){this.jdbc=jdbc;this.tenants=tenants;}
    public record MovementInput(@NotNull UUID companyId,@NotNull LocalDate effectiveDate,
        @NotBlank @Pattern(regexp="MONTHLY|DAILY|HOURLY") String payBasis,
        @NotNull @DecimalMin("0.01") @Digits(integer=13,fraction=2) BigDecimal minimumRate,
        @NotBlank @Size(max=250) String reason){}
    public record ApplyInput(@NotNull UUID companyId,@NotNull LocalDate effectiveDate,
        @NotBlank @Pattern(regexp="MONTHLY|DAILY|HOURLY") String payBasis,
        @NotNull @DecimalMin("0.01") @Digits(integer=13,fraction=2) BigDecimal minimumRate,
        @NotBlank @Size(max=250) String reason,@Min(1) int expectedCount){}
    private void authorize(HrmUserPrincipal user){if(user==null||!user.isEnabled()||user.getAuthorities().stream().noneMatch(a->Set.of("ROLE_TENANT_ADMIN","ROLE_HR_ADMIN","ROLE_MFA_VERIFIED").contains(a.getAuthority()))||!user.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_MFA_VERIFIED"))||!user.getAuthorities().stream().anyMatch(a->Set.of("ROLE_TENANT_ADMIN","ROLE_HR_ADMIN").contains(a.getAuthority())))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"HR administrator with MFA required");}
    private List<Map<String,Object>> rows(UUID tenant,MovementInput in){return jdbc.queryForList("""
        SELECT e.id AS employee_id,e.employee_number,e.first_name,e.last_name,e.hire_date,
          l.employee_id AS timekeeping_id,c.amount AS current_amount,c.pay_basis AS current_basis,
          (SELECT max(x.effective_from) FROM compensation x WHERE x.tenant_id=e.tenant_id AND x.employee_id=l.employee_id) AS legacy_latest,
          (SELECT max(h.effective_date) FROM personnel_compensation_history h WHERE h.tenant_id=e.tenant_id AND h.employee_id=e.id) AS personnel_latest
        FROM personnel_employee e
        LEFT JOIN personnel_timekeeping_employee l ON l.tenant_id=e.tenant_id AND l.personnel_employee_id=e.id
        LEFT JOIN LATERAL (SELECT base_rate AS amount,pay_basis,effective_from FROM compensation x
          WHERE x.tenant_id=e.tenant_id AND x.employee_id=l.employee_id AND x.effective_from<=?
          ORDER BY x.effective_from DESC LIMIT 1) c ON true
        WHERE e.tenant_id=? AND e.company_id=? AND e.employment_status IN ('ACTIVE','ON_LEAVE')
          AND e.hire_date<=? ORDER BY e.employee_number
        """,in.effectiveDate(),tenant,in.companyId(),in.effectiveDate());}
    @GetMapping("/preview")
    public Map<String,Object> preview(@AuthenticationPrincipal HrmUserPrincipal user,@RequestParam UUID companyId,
        @RequestParam LocalDate effectiveDate,@RequestParam String payBasis,@RequestParam BigDecimal minimumRate){
        authorize(user); MovementInput in=new MovementInput(companyId,effectiveDate,payBasis,minimumRate,"preview");
        return tenants.inTenant(user.tenantId(),()->summarize(user.tenantId(),in));
    }
    private Map<String,Object> summarize(UUID tenant,MovementInput in){
        List<Map<String,Object>> all=rows(tenant,in);List<Map<String,Object>> changes=new ArrayList<>();List<Map<String,Object>> excluded=new ArrayList<>();
        for(Map<String,Object> r:all){String basis=(String)r.get("current_basis");BigDecimal current=(BigDecimal)r.get("current_amount");Object latest=r.get("personnel_latest");Object legacyLatest=r.get("legacy_latest");String reason=null;
            if(r.get("timekeeping_id")==null)reason="Missing payroll employee link";
            else if(current==null)reason="No current payroll compensation";
            else if(!in.payBasis().equals(basis))reason="Different pay basis: "+basis;
            else if(current.compareTo(in.minimumRate())>=0)reason="Already at or above minimum rate";
            else if(latest instanceof LocalDate d&&!in.effectiveDate().isAfter(d))reason="Effective date must follow existing personnel compensation";
            else if(legacyLatest instanceof LocalDate d&&!in.effectiveDate().isAfter(d))reason="Effective date must follow existing payroll compensation";
            Map<String,Object> item=new LinkedHashMap<>();item.put("employeeId",r.get("employee_id"));item.put("employeeNumber",r.get("employee_number"));item.put("firstName",r.get("first_name"));item.put("lastName",r.get("last_name"));item.put("currentRate",current);item.put("proposedRate",current==null?null:current.max(in.minimumRate()));item.put("timekeepingEmployeeId",r.get("timekeeping_id"));item.put("payBasis",basis);item.put("exclusionReason",reason);
            if(reason==null)changes.add(item);else excluded.add(item);
        }
        return Map.of("effectiveDate",in.effectiveDate(),"minimumRate",in.minimumRate(),"payBasis",in.payBasis(),"employeeCount",all.size(),"changeCount",changes.size(),"changes",changes,"excluded",excluded);
    }
    @PostMapping
    public Map<String,Object> apply(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody ApplyInput request){
        authorize(user);MovementInput in=new MovementInput(request.companyId(),request.effectiveDate(),request.payBasis(),request.minimumRate(),request.reason());return tenants.inTenant(user.tenantId(),()->{
            Map<String,Object> preview=summarize(user.tenantId(),in);@SuppressWarnings("unchecked") List<Map<String,Object>> changes=(List<Map<String,Object>>)preview.get("changes");
            if(changes.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"No employees need this salary movement");
            if(changes.size()!=request.expectedCount())throw new ResponseStatusException(HttpStatus.CONFLICT,"The affected employee count changed after preview. Review the updated preview before applying.");
            for(Map<String,Object> r:changes){UUID personnelId=(UUID)r.get("employeeId"),legacyId=(UUID)r.get("timekeepingEmployeeId");BigDecimal amount=(BigDecimal)r.get("proposedRate");
                jdbc.update("INSERT INTO personnel_compensation_history(tenant_id,employee_id,effective_date,amount,pay_basis,reason,recorded_by) VALUES(?,?,?,?,?,?,?)",user.tenantId(),personnelId,in.effectiveDate(),amount,in.payBasis(),in.reason().trim(),user.id());
                jdbc.update("UPDATE compensation SET effective_to=? ,updated_at=now() WHERE tenant_id=? AND employee_id=? AND effective_to IS NULL AND effective_from<?",in.effectiveDate().minusDays(1),user.tenantId(),legacyId,in.effectiveDate());
                jdbc.update("INSERT INTO compensation(tenant_id,employee_id,effective_from,pay_basis,base_rate,reason) VALUES(?,?,?,?,?,?)",user.tenantId(),legacyId,in.effectiveDate(),in.payBasis(),amount,in.reason().trim());
                jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,'BULK_COMPENSATION_MOVEMENT','personnel_employee',?)",user.tenantId(),user.id(),personnelId);
            }
            return Map.of("effectiveDate",in.effectiveDate(),"changedCount",changes.size(),"minimumRate",in.minimumRate(),"payBasis",in.payBasis());
        });
    }
}
