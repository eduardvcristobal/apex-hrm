package com.apex.hrm.selfservice;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import com.apex.hrm.approvals.ApprovalWorkflowService;
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
    private final ApprovalWorkflowService approvals;
    public SelfServiceController(JdbcTemplate jdbc,TenantJdbcExecutor tenants,LeaveService leaves,OvertimeService overtime,AttendanceService attendance,ApprovalWorkflowService approvals) { this.jdbc=jdbc;this.tenants=tenants;this.leaves=leaves;this.overtime=overtime;this.attendance=attendance;this.approvals=approvals; }
    public record LeaveInput(@NotNull UUID leaveTypeId,@NotNull LocalDate dateFrom,@NotNull LocalDate dateTo,@NotNull @DecimalMin("0.25") BigDecimal days,boolean halfDay,@Size(max=3000) String reason) {}
    public record OvertimeInput(@NotNull LocalDate workDate,@Min(1) @Max(1440) int minutes,@NotBlank @Size(max=3000) String reason) {}
    public record CorrectionInput(@NotNull LocalDate workDate,Instant requestedIn,Instant requestedOut,@NotBlank @Size(max=3000) String reason) {}
    public record Decision(@NotBlank @Pattern(regexp="APPROVE|REJECT") String decision,@Min(1) @Max(1440) Integer approvedMinutes) {}
    private record EmployeeRef(UUID personnelId,UUID timekeepingId) {}
    private ResponseStatusException denied() { return new ResponseStatusException(HttpStatus.FORBIDDEN,"An employee profile must be linked to this account"); }
    private boolean has(HrmUserPrincipal user,String role) { return user.getAuthorities().stream().anyMatch(a->a.getAuthority().equals(role)); }
    private void authenticated(HrmUserPrincipal user) { if(user==null||!user.isEnabled()) throw denied(); }
    private EmployeeRef employee(HrmUserPrincipal user) {
        List<EmployeeRef> links=jdbc.query("SELECT x.personnel_id,l.employee_id FROM (SELECT employee_id AS personnel_id FROM ld_employee_account WHERE tenant_id=? AND identity_id=? UNION ALL SELECT p.employee_id FROM performance_employee_identity p WHERE p.tenant_id=? AND p.identity_id=? AND NOT EXISTS(SELECT 1 FROM ld_employee_account a WHERE a.tenant_id=p.tenant_id AND a.identity_id=p.identity_id)) x JOIN personnel_timekeeping_employee l ON l.tenant_id=? AND l.personnel_employee_id=x.personnel_id",(rs,i)->new EmployeeRef(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)),user.tenantId(),user.id(),user.tenantId(),user.id(),user.tenantId());
        if(links.isEmpty()) throw denied(); return links.get(0);
    }
    private Map<String,Object> employeeRow(UUID employeeId,UUID tenant) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT e.id,e.employee_number,e.first_name,e.middle_name,e.last_name,e.preferred_name,e.hire_date,e.employment_status,e.employment_type,e.company_id,c.legal_name AS company_name,e.department_id,d.department_name,e.supervisor_id FROM personnel_employee e JOIN company c ON c.tenant_id=e.tenant_id AND c.id=e.company_id LEFT JOIN personnel_department d ON d.tenant_id=e.tenant_id AND d.id=e.department_id WHERE e.tenant_id=? AND e.id=?",tenant,employeeId);
        if(rows.isEmpty()) throw denied(); return rows.get(0);
    }
    @GetMapping("/me") public Map<String,Object> me(@AuthenticationPrincipal HrmUserPrincipal user) {
        authenticated(user); return tenants.inTenant(user.tenantId(),()->{ EmployeeRef ids=employee(user); Map<String,Object> profile=employeeRow(ids.personnelId(),user.tenantId());
            List<Map<String,Object>> balances=jdbc.queryForList("SELECT t.id,t.code,t.name,t.paid,t.annual_entitlement AS annual_entitlement,coalesce(sum(l.days),0) AS balance FROM leave_type t LEFT JOIN leave_ledger l ON l.tenant_id=t.tenant_id AND l.leave_type_id=t.id AND l.employee_id=? WHERE t.tenant_id=? GROUP BY t.id ORDER BY t.name",ids.timekeepingId(),user.tenantId());
            List<Map<String,Object>> dtr=jdbc.queryForList("SELECT work_date,day_type,first_in,last_out,regular_minutes,late_minutes,undertime_minutes,ot_approved_minutes,absent,paid_leave,status FROM dtr_day WHERE tenant_id=? AND employee_id=? AND work_date>=current_date-30 ORDER BY work_date DESC",user.tenantId(),ids.timekeepingId());
            List<Map<String,Object>> payroll=jdbc.queryForList("SELECT p.id,r.run_type AS run_type,pp.start_date AS period_start,pp.end_date AS period_end,pp.pay_date AS pay_date,pe.net_pay AS net_pay FROM payslip p JOIN payroll_run r ON r.tenant_id=p.tenant_id AND r.id=p.run_id JOIN pay_period pp ON pp.tenant_id=r.tenant_id AND pp.id=r.pay_period_id JOIN payroll_employee pe ON pe.tenant_id=p.tenant_id AND pe.id=p.payroll_employee_id WHERE p.tenant_id=? AND p.employee_id=? ORDER BY pp.pay_date DESC LIMIT 12",user.tenantId(),ids.timekeepingId());
            return Map.of("profile",profile,"leaveBalances",balances,"attendance",dtr,"payslips",payroll,"leaveRequests",jdbc.queryForList("SELECT r.id,r.date_from,r.date_to,r.days,r.reason,r.status,t.name AS leave_type FROM leave_request r JOIN leave_type t ON t.tenant_id=r.tenant_id AND t.id=r.leave_type_id WHERE r.tenant_id=? AND r.employee_id=? ORDER BY r.created_at DESC LIMIT 100",user.tenantId(),ids.timekeepingId()),"overtimeRequests",jdbc.queryForList("SELECT id,work_date,minutes_requested,approved_minutes,reason,status FROM overtime_request WHERE tenant_id=? AND employee_id=? ORDER BY created_at DESC LIMIT 100",user.tenantId(),ids.timekeepingId()),"attendanceCorrections",jdbc.queryForList("SELECT id,work_date,requested_in,requested_out,reason,status FROM attendance_correction WHERE tenant_id=? AND employee_id=? ORDER BY created_at DESC LIMIT 100",user.tenantId(),ids.timekeepingId()));
        });
    }
    @GetMapping("/leave-types") public List<Map<String,Object>> leaveTypes(@AuthenticationPrincipal HrmUserPrincipal user) {
        authenticated(user); return tenants.inTenant(user.tenantId(),()->{
            UUID id=employee(user).timekeepingId();
            return jdbc.queryForList("SELECT t.id,t.code,t.name,t.paid,t.annual_entitlement AS annual_entitlement,coalesce(sum(l.days),0) AS balance FROM leave_type t LEFT JOIN leave_ledger l ON l.tenant_id=t.tenant_id AND l.leave_type_id=t.id AND l.employee_id=? WHERE t.tenant_id=? GROUP BY t.id ORDER BY t.name",id,user.tenantId());
        });
    }
    @PostMapping("/leave-requests") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> fileLeave(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody LeaveInput in) {
        authenticated(user); return tenants.inTenant(user.tenantId(),()->{ EmployeeRef ids=employee(user); var req=leaves.file(ids.timekeepingId(),in.leaveTypeId(),in.dateFrom(),in.dateTo(),in.days(),in.halfDay(),in.reason()); UUID instance=approvals.start(user.tenantId(),user.id(),ids.personnelId(),"LEAVE","leave_request",req.getId(),in.days()+" day leave request from "+in.dateFrom()+" to "+in.dateTo()); jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,'SELF_SERVICE_LEAVE_FILED','leave_request',?)",user.tenantId(),user.id(),req.getId()); return Map.of("id",req.getId(),"approvalId",instance,"status","PENDING"); });
    }
    @PostMapping("/overtime-requests") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> fileOvertime(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody OvertimeInput in) {
        authenticated(user); return tenants.inTenant(user.tenantId(),()->{ EmployeeRef ids=employee(user); var req=overtime.file(ids.timekeepingId(),in.workDate(),in.minutes(),in.reason()); UUID instance=approvals.start(user.tenantId(),user.id(),ids.personnelId(),"OVERTIME","overtime_request",req.getId(),in.minutes()+" minutes overtime request on "+in.workDate()); jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,'SELF_SERVICE_OVERTIME_FILED','overtime_request',?)",user.tenantId(),user.id(),req.getId()); return Map.of("id",req.getId(),"approvalId",instance,"status","PENDING"); });
    }
    @PostMapping("/attendance-corrections") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> correction(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody CorrectionInput in) {
        authenticated(user); return tenants.inTenant(user.tenantId(),()->{ EmployeeRef ids=employee(user); var req=attendance.requestCorrection(ids.timekeepingId(),in.workDate(),in.requestedIn(),in.requestedOut(),in.reason()); UUID instance=approvals.start(user.tenantId(),user.id(),ids.personnelId(),"ATTENDANCE_CORRECTION","attendance_correction",req.getId(),"Attendance correction request for "+in.workDate()); jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,'SELF_SERVICE_CORRECTION_FILED','attendance_correction',?)",user.tenantId(),user.id(),req.getId()); return Map.of("id",req.getId(),"approvalId",instance,"status","PENDING"); });
    }
    @GetMapping("/manager/approvals") public List<Map<String,Object>> approvals(@AuthenticationPrincipal HrmUserPrincipal user) {
        authenticated(user); if(!has(user,"ROLE_MANAGER")) throw denied(); return approvals.inbox(user.tenantId(),user.id());
    }
    @PostMapping("/manager/approvals/{type}/{id}") public void decide(@PathVariable String type,@PathVariable UUID id) {
        throw new ResponseStatusException(HttpStatus.GONE,"Use the assigned task in the Approval center");
    }
}
