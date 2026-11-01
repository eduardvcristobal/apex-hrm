package com.apex.hrm.recruitment;

import com.apex.hrm.approvals.ApprovalWorkflowService;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/recruitment/headcount-requests")
public class HeadcountController {
    private final JdbcTemplate jdbc;private final TenantJdbcExecutor tenants;private final ApprovalWorkflowService approvals;
    public HeadcountController(JdbcTemplate jdbc,TenantJdbcExecutor tenants,ApprovalWorkflowService approvals){this.jdbc=jdbc;this.tenants=tenants;this.approvals=approvals;}
    public record Request(@NotBlank @Size(max=180) String roleTitle,@NotBlank @Size(max=40) String employmentType,@Min(1) @Max(100) int positions,LocalDate targetDate,@NotBlank @Size(max=10000) String businessJustification){}
    private boolean has(HrmUserPrincipal u,String role){return u.getAuthorities().stream().anyMatch(a->a.getAuthority().equals(role));}
    private void auth(HrmUserPrincipal u){if(u==null||!u.isEnabled()||!has(u,"ROLE_MFA_VERIFIED")||(!has(u,"ROLE_MANAGER")&&!has(u,"ROLE_HR_ADMIN")&&!has(u,"ROLE_TENANT_ADMIN")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"A verified manager or HR account is required");}
    private UUID employee(HrmUserPrincipal user){List<UUID> ids=jdbc.query("SELECT employee_id FROM ld_employee_account WHERE tenant_id=? AND identity_id=? UNION ALL SELECT p.employee_id FROM performance_employee_identity p WHERE p.tenant_id=? AND p.identity_id=? AND NOT EXISTS(SELECT 1 FROM ld_employee_account l WHERE l.tenant_id=p.tenant_id AND l.identity_id=p.identity_id)",(rs,n)->rs.getObject(1,UUID.class),user.tenantId(),user.id(),user.tenantId(),user.id());if(ids.isEmpty())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Link your account to an employee profile before requesting headcount");return ids.get(0);}
    @GetMapping public List<Map<String,Object>> list(@AuthenticationPrincipal HrmUserPrincipal user){auth(user);return tenants.inTenant(user.tenantId(),()->{boolean all=has(user,"ROLE_HR_ADMIN")||has(user,"ROLE_TENANT_ADMIN");UUID requester=all?null:employee(user);return jdbc.queryForList("SELECT r.id,r.role_title,r.employment_type,r.positions,r.target_date,r.business_justification,r.status,r.approved_job_id,r.created_at,d.department_name,e.first_name,e.last_name,i.status AS approval_status,i.current_step FROM recruitment_headcount_request r JOIN personnel_department d ON d.tenant_id=r.tenant_id AND d.id=r.department_id JOIN personnel_employee e ON e.tenant_id=r.tenant_id AND e.id=r.requested_by_employee_id LEFT JOIN approval_instance i ON i.tenant_id=r.tenant_id AND i.process_code='HEADCOUNT' AND i.business_id=r.id WHERE r.tenant_id=? AND (? OR r.requested_by_employee_id=?) ORDER BY r.created_at DESC LIMIT 1000",user.tenantId(),all,requester);});}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> create(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody Request in){auth(user);return tenants.inTenant(user.tenantId(),()->{UUID emp=employee(user);List<Map<String,Object>> rows=jdbc.queryForList("SELECT company_id,department_id FROM personnel_employee WHERE tenant_id=? AND id=? AND department_id IS NOT NULL",user.tenantId(),emp);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Assign the requester to a department before requesting headcount");UUID id=UUID.randomUUID();jdbc.update("INSERT INTO recruitment_headcount_request(id,tenant_id,company_id,department_id,requested_by_employee_id,role_title,employment_type,positions,target_date,business_justification) VALUES(?,?,?,?,?,?,?,?,?,?)",id,user.tenantId(),rows.get(0).get("company_id"),rows.get(0).get("department_id"),emp,in.roleTitle().trim(),in.employmentType(),in.positions(),in.targetDate(),in.businessJustification().trim());UUID workflow=approvals.start(user.tenantId(),user.id(),emp,"HEADCOUNT","recruitment_headcount_request",id,in.positions()+" headcount position(s): "+in.roleTitle().trim());jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,'HEADCOUNT_REQUEST_SUBMITTED','recruitment_headcount_request',?)",user.tenantId(),user.id(),id);return Map.of("id",id,"approvalId",workflow,"status","PENDING");});}
}
