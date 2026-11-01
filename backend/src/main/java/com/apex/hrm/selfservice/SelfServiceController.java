package com.apex.hrm.selfservice;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import com.apex.hrm.timekeeping.service.AttendanceService;
import com.apex.hrm.timekeeping.service.LeaveService;
import com.apex.hrm.timekeeping.service.OvertimeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Identity-linked employee portal and a direct-report approval inbox. */
@RestController
@RequestMapping("/api/v1/self-service")
public class SelfServiceController {
    private final JdbcTemplate jdbc; private final TenantJdbcExecutor tenants;
    private final LeaveService leaves; private final OvertimeService overtime; private final AttendanceService attendance;
    public SelfServiceController(JdbcTemplate jdbc,TenantJdbcExecutor tenants,LeaveService leaves,OvertimeService overtime,AttendanceService attendance) { this.jdbc=jdbc;this.tenants=tenants;this.leaves=leaves;this.overtime=overtime;this.attendance=attendance; }
    public record LeaveInput(@NotNull UUID leaveTypeId,@NotNull LocalDate dateFrom,@NotNull LocalDate dateTo,@NotNull @DecimalMin("0.25") BigDecimal days,boolean halfDay,@Size(max=3000) String reason) {}
    public record OvertimeInput(@NotNull LocalDate workDate,@Min(1) @Max(1440) int minutes,@NotBlank @Size(max=3000) String reason) {}
    public record CorrectionInput(@NotNull LocalDate workDate,Instant requestedIn,Instant requestedOut,@NotBlank @Size(max=3000) String reason) {}
    public record Decision(@NotBlank @Pattern(regexp="APPROVE|REJECT") String decision,@Min(1) @Max(1440) Integer approvedMinutes) {}
    private ResponseStatusException denied() { return new ResponseStatusException(HttpStatus.FORBIDDEN,"An employee profile must be linked to this account"); }
    private boolean has(HrmUserPrincipal user,String role) { return user.getAuthorities().stream().anyMatch(a->a.getAuthority().equals(role)); }
    private void authenticated(HrmUserPrincipal user) { if(user==null||!user.isEnabled()) throw denied(); }
    private UUID employee(HrmUserPrincipal user) {
        List<UUID> ids=jdbc.query("SELECT employee_id FROM ld_employee_account WHERE tenant_id=? AND identity_id=?",(rs,i)->rs.getObject(1,UUID.class),user.tenantId(),user.id());
        if(ids.isEmpty()) ids=jdbc.query("SELECT employee_id FROM performance_employee_identity WHERE tenant_id=? AND identity_id=?",(rs,i)->rs.getObject(1,UUID.class),user.tenantId(),user.id());
        if(ids.isEmpty()) throw denied(); return ids.get(0);
    }
    private Map<String,Object> employeeRow(UUID employeeId,UUID tenant) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT e.id,e.employee_number,e.first_name,e.middle_name,e.last_name,e.preferred_name,e.hire_date,e.employment_status,e.employment_type,e.company_id,c.legal_name AS company_name,e.department_id,d.department_name,e.supervisor_id FROM personnel_employee e JOIN company c ON c.tenant_id=e.tenant_id AND c.id=e.company_id LEFT JOIN personnel_department d ON d.tenant_id=e.tenant_id AND d.id=e.department_id WHERE e.tenant_id=? AND e.id=?",tenant,employeeId);
        if(rows.isEmpty()) throw denied(); return rows.get(0);
    }
    private boolean isManager(HrmUserPrincipal u) { return has(u,"ROLE_MANAGER"); }
    private void requireDirectReport(HrmUserPrincipal u,UUID employeeId) {
        if(!isManager(u)||!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM personnel_employee WHERE tenant_id=? AND id=? AND supervisor_id=?)",Boolean.class,u.tenantId(),employeeId,employee(u)))) throw denied();
    }
    @GetMapping("/me") public Map<String,Object> me(@AuthenticationPrincipal HrmUserPrincipal user) {
        authenticated(user); return tenants.inTenant(user.tenantId(),()->{ UUID id=employee(user); Map<String,Object> profile=employeeRow(id,user.tenantId());
            List<Map<String,Object>> balances=jdbc.queryForList("SELECT t.id,t.code,t.name,t.paid,t.annual_entitlement AS annual_entitlement,coalesce(sum(l.days),0) AS balance FROM leave_type t LEFT JOIN leave_ledger l ON l.tenant_id=t.tenant_id AND l.leave_type_id=t.id AND l.employee_id=? WHERE t.tenant_id=? GROUP BY t.id ORDER BY t.name",id,user.tenantId());
            List<Map<String,Object>> dtr=jdbc.queryForList("SELECT work_date,day_type,first_in,last_out,regular_minutes,late_minutes,undertime_minutes,ot_approved_minutes,absent,paid_leave,status FROM dtr_day WHERE tenant_id=? AND employee_id=? AND work_date>=current_date-30 ORDER BY work_date DESC",user.tenantId(),id);
            List<Map<String,Object>> payroll=jdbc.queryForList("SELECT p.id,r.run_type AS run_type,pp.start_date AS period_start,pp.end_date AS period_end,pp.pay_date AS pay_date,pe.net_pay AS net_pay FROM payslip p JOIN payroll_run r ON r.tenant_id=p.tenant_id AND r.id=p.run_id JOIN pay_period pp ON pp.tenant_id=r.tenant_id AND pp.id=r.pay_period_id JOIN payroll_employee pe ON pe.tenant_id=p.tenant_id AND pe.id=p.payroll_employee_id WHERE p.tenant_id=? AND p.employee_id=? ORDER BY pp.pay_date DESC LIMIT 12",user.tenantId(),id);
            return Map.of("profile",profile,"leaveBalances",balances,"attendance",dtr,"payslips",payroll,"leaveRequests",jdbc.queryForList("SELECT r.id,r.date_from,r.date_to,r.days,r.reason,r.status,t.name AS leave_type FROM leave_request r JOIN leave_type t ON t.tenant_id=r.tenant_id AND t.id=r.leave_type_id WHERE r.tenant_id=? AND r.employee_id=? ORDER BY r.created_at DESC LIMIT 100",user.tenantId(),id),"overtimeRequests",jdbc.queryForList("SELECT id,work_date,minutes_requested,approved_minutes,reason,status FROM overtime_request WHERE tenant_id=? AND employee_id=? ORDER BY created_at DESC LIMIT 100",user.tenantId(),id),"attendanceCorrections",jdbc.queryForList("SELECT id,work_date,requested_in,requested_out,reason,status FROM attendance_correction WHERE tenant_id=? AND employee_id=? ORDER BY created_at DESC LIMIT 100",user.tenantId(),id));
        });
    }
    @GetMapping("/leave-types") public List<Map<String,Object>> leaveTypes(@AuthenticationPrincipal HrmUserPrincipal user) {
        authenticated(user); return tenants.inTenant(user.tenantId(),()->{
            UUID id=employee(user);
            return jdbc.queryForList("SELECT t.id,t.code,t.name,t.paid,t.annual_entitlement AS annual_entitlement,coalesce(sum(l.days),0) AS balance FROM leave_type t LEFT JOIN leave_ledger l ON l.tenant_id=t.tenant_id AND l.leave_type_id=t.id AND l.employee_id=? WHERE t.tenant_id=? GROUP BY t.id ORDER BY t.name",id,user.tenantId());
        });
    }
    @PostMapping("/leave-requests") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> fileLeave(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody LeaveInput in) {
        authenticated(user); return tenants.inTenant(user.tenantId(),()->{ UUID id=employee(user); var req=leaves.file(id,in.leaveTypeId(),in.dateFrom(),in.dateTo(),in.days(),in.halfDay(),in.reason()); jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,'SELF_SERVICE_LEAVE_FILED','leave_request',?)",user.tenantId(),user.id(),req.getId()); return Map.of("id",req.getId(),"status",req.getStatus()); });
    }
    @PostMapping("/overtime-requests") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> fileOvertime(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody OvertimeInput in) {
        authenticated(user); return tenants.inTenant(user.tenantId(),()->{ UUID id=employee(user); var req=overtime.file(id,in.workDate(),in.minutes(),in.reason()); jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,'SELF_SERVICE_OVERTIME_FILED','overtime_request',?)",user.tenantId(),user.id(),req.getId()); return Map.of("id",req.getId(),"status",req.getStatus()); });
    }
    @PostMapping("/attendance-corrections") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> correction(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody CorrectionInput in) {
        authenticated(user); return tenants.inTenant(user.tenantId(),()->{ UUID id=employee(user); var req=attendance.requestCorrection(id,in.workDate(),in.requestedIn(),in.requestedOut(),in.reason()); jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,'SELF_SERVICE_CORRECTION_FILED','attendance_correction',?)",user.tenantId(),user.id(),req.getId()); return Map.of("id",req.getId(),"status",req.getStatus()); });
    }
    @GetMapping("/manager/approvals") public Map<String,Object> approvals(@AuthenticationPrincipal HrmUserPrincipal user) {
        authenticated(user); if(!isManager(user)) throw denied(); return tenants.inTenant(user.tenantId(),()->{ UUID manager=employee(user); List<Map<String,Object>> reports=jdbc.queryForList("SELECT id FROM personnel_employee WHERE tenant_id=? AND supervisor_id=? AND employment_status IN ('ACTIVE','ON_LEAVE')",user.tenantId(),manager); if(reports.isEmpty()) return Map.of("leaveRequests",List.of(),"overtimeRequests",List.of(),"attendanceCorrections",List.of());
            String ids=String.join(",",Collections.nCopies(reports.size(),"?")); List<Object> args=new ArrayList<>(); args.add(user.tenantId()); reports.forEach(r->args.add(r.get("id")));
            String where="tenant_id=? AND employee_id IN ("+ids+") AND status='PENDING'";
            return Map.of("leaveRequests",jdbc.queryForList("SELECT r.id,r.employee_id,r.date_from,r.date_to,r.days,r.reason,t.name AS leave_type,e.first_name,e.last_name,e.employee_number FROM leave_request r JOIN leave_type t ON t.tenant_id=r.tenant_id AND t.id=r.leave_type_id JOIN personnel_employee e ON e.tenant_id=r.tenant_id AND e.id=r.employee_id WHERE r."+where+" ORDER BY r.created_at",args.toArray()),"overtimeRequests",jdbc.queryForList("SELECT r.id,r.employee_id,r.work_date,r.minutes_requested,r.reason,e.first_name,e.last_name,e.employee_number FROM overtime_request r JOIN personnel_employee e ON e.tenant_id=r.tenant_id AND e.id=r.employee_id WHERE r."+where+" ORDER BY r.created_at",args.toArray()),"attendanceCorrections",jdbc.queryForList("SELECT r.id,r.employee_id,r.work_date,r.requested_in,r.requested_out,r.reason,e.first_name,e.last_name,e.employee_number FROM attendance_correction r JOIN personnel_employee e ON e.tenant_id=r.tenant_id AND e.id=r.employee_id WHERE r."+where+" ORDER BY r.created_at",args.toArray()));
        });
    }
    @PostMapping("/manager/approvals/{type}/{id}") public Map<String,Object> decide(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable String type,@PathVariable UUID id,@Valid @RequestBody Decision decision) {
        authenticated(user); return tenants.inTenant(user.tenantId(),()->{ UUID employeeId=switch(type){ case "leave" -> jdbc.query("SELECT employee_id FROM leave_request WHERE tenant_id=? AND id=?",(rs,n)->rs.getObject(1,UUID.class),user.tenantId(),id).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Request not found")); case "overtime" -> jdbc.query("SELECT employee_id FROM overtime_request WHERE tenant_id=? AND id=?",(rs,n)->rs.getObject(1,UUID.class),user.tenantId(),id).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Request not found")); case "correction" -> jdbc.query("SELECT employee_id FROM attendance_correction WHERE tenant_id=? AND id=?",(rs,n)->rs.getObject(1,UUID.class),user.tenantId(),id).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Request not found")); default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown approval type"); }; requireDirectReport(user,employeeId);
            switch(type){case "leave" -> { if(decision.decision().equals("APPROVE")) leaves.approve(id,user.id()); else leaves.reject(id,user.id()); } case "overtime" -> { if(decision.decision().equals("APPROVE")) overtime.approve(id,decision.approvedMinutes(),user.id()); else overtime.reject(id,user.id()); } case "correction" -> { if(decision.decision().equals("APPROVE")) attendance.approveCorrection(id,user.id()); else attendance.rejectCorrection(id,user.id()); } }
            jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,?,?,?)",user.tenantId(),user.id(),"MANAGER_"+type.toUpperCase(Locale.ROOT)+"_"+decision.decision(),type+"_request",id); return Map.of("id",id,"status",decision.decision().equals("APPROVE")?"APPROVED":"REJECTED"); });
    }
}
