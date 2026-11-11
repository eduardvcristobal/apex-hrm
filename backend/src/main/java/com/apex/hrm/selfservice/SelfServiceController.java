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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
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
    public record PunchInput(@NotBlank @Pattern(regexp="IN|OUT") String logType,
                             @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,
                             @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude,
                             @DecimalMin("0.0") BigDecimal locationAccuracyM,
                             @Size(max=1398104) String photoBase64,@Size(max=255) String deviceId) {}
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
            List<Map<String,Object>> balances=jdbc.queryForList("SELECT t.id,t.code,t.name,coalesce(profile_rule.profile_name,'Default') AS profile_name,coalesce(profile_rule.employee_class,'GENERAL') AS employee_class,coalesce(profile_rule.paid,t.paid) AS paid,coalesce(profile_rule.annual_entitlement,t.annual_entitlement) AS annual_entitlement,coalesce(profile_rule.convertible,t.convertible) AS convertible,coalesce(profile_rule.carry_over_max,t.carry_over_max) AS carry_over_max,coalesce(profile_rule.hire_proration_enabled,t.hire_proration_enabled) AS hire_proration_enabled,coalesce(sum(l.days),0) AS balance FROM leave_type t LEFT JOIN leave_ledger l ON l.tenant_id=t.tenant_id AND l.leave_type_id=t.id AND l.employee_id=? AND l.entry_date<=current_date LEFT JOIN LATERAL (SELECT be.paid,be.annual_entitlement,be.convertible,be.carry_over_max,be.hire_proration_enabled,p.name AS profile_name,p.employee_class FROM employee_leave_benefit_assignment a JOIN leave_benefit_profile p ON p.tenant_id=a.tenant_id AND p.id=a.profile_id JOIN leave_benefit_entitlement be ON be.tenant_id=a.tenant_id AND be.profile_id=a.profile_id AND be.leave_type_id=t.id WHERE a.tenant_id=t.tenant_id AND a.employee_id=? AND a.effective_from<=current_date AND (a.effective_to IS NULL OR a.effective_to>=current_date) AND be.effective_from<=current_date AND (be.effective_to IS NULL OR be.effective_to>=current_date) ORDER BY a.effective_from DESC,be.effective_from DESC LIMIT 1) profile_rule ON true WHERE t.tenant_id=? GROUP BY t.id,profile_rule.profile_name,profile_rule.employee_class,profile_rule.paid,profile_rule.annual_entitlement,profile_rule.convertible,profile_rule.carry_over_max,profile_rule.hire_proration_enabled ORDER BY t.name",ids.timekeepingId(),ids.timekeepingId(),user.tenantId());
            List<Map<String,Object>> dtr=jdbc.queryForList("SELECT work_date,day_type,first_in,last_out,regular_minutes,late_minutes,undertime_minutes,ot_approved_minutes,absent,paid_leave,status FROM dtr_day WHERE tenant_id=? AND employee_id=? AND work_date>=current_date-30 ORDER BY work_date DESC",user.tenantId(),ids.timekeepingId());
            List<Map<String,Object>> punches=jdbc.queryForList("SELECT id,log_time,log_type,source,latitude,longitude,location_accuracy_m,photo_ref,status,remarks FROM time_log WHERE tenant_id=? AND employee_id=? ORDER BY log_time DESC LIMIT 20",user.tenantId(),ids.timekeepingId());
            List<Map<String,Object>> payroll=jdbc.queryForList("SELECT p.id,p.snapshot,p.acknowledged_at,r.status AS run_status,r.run_type AS run_type,pp.start_date AS period_start,pp.end_date AS period_end,pp.pay_date AS pay_date,pe.net_pay AS net_pay FROM payslip p JOIN payroll_run r ON r.tenant_id=p.tenant_id AND r.id=p.run_id JOIN pay_period pp ON pp.tenant_id=r.tenant_id AND pp.id=r.pay_period_id JOIN payroll_employee pe ON pe.tenant_id=p.tenant_id AND pe.id=p.payroll_employee_id WHERE p.tenant_id=? AND p.employee_id=? AND r.status IN ('EMPLOYEE_REVIEW','LOCKED','PAID') ORDER BY pp.pay_date DESC LIMIT 1000",user.tenantId(),ids.timekeepingId());
            return Map.of("profile",profile,"leaveBalances",balances,"attendance",dtr,"punches",punches,"payslips",payroll,"leaveRequests",jdbc.queryForList("SELECT r.id,r.date_from,r.date_to,r.days,r.reason,r.status,t.name AS leave_type FROM leave_request r JOIN leave_type t ON t.tenant_id=r.tenant_id AND t.id=r.leave_type_id WHERE r.tenant_id=? AND r.employee_id=? ORDER BY r.created_at DESC LIMIT 100",user.tenantId(),ids.timekeepingId()),"overtimeRequests",jdbc.queryForList("SELECT id,work_date,minutes_requested,approved_minutes,reason,status FROM overtime_request WHERE tenant_id=? AND employee_id=? ORDER BY created_at DESC LIMIT 100",user.tenantId(),ids.timekeepingId()),"attendanceCorrections",jdbc.queryForList("SELECT id,work_date,requested_in,requested_out,reason,status FROM attendance_correction WHERE tenant_id=? AND employee_id=? ORDER BY created_at DESC LIMIT 100",user.tenantId(),ids.timekeepingId()));
        });
    }
    @PostMapping("/attendance-punches") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> punch(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody PunchInput in) {
        authenticated(user);return tenants.inTenant(user.tenantId(),()->{
            byte[] photo=null;UUID photoId=null;
            if(in.photoBase64()!=null&&!in.photoBase64().isBlank()) {
                try { photo=Base64.getDecoder().decode(in.photoBase64()); }
                catch(IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Photo must be valid base64 JPEG data"); }
                if(photo.length>1_048_576||photo.length<3||photo[0]!=(byte)0xff||photo[1]!=(byte)0xd8||photo[2]!=(byte)0xff)
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Photo must be a JPEG image no larger than 1 MB");
                photoId=UUID.randomUUID();
            }
            EmployeeRef ids=employee(user);
            var log=attendance.clock(ids.timekeepingId(),in.logType(),"WEB",in.latitude(),in.longitude(),in.locationAccuracyM(),in.deviceId(),photoId==null?null:photoId.toString());
            if(photo!=null) jdbc.update("INSERT INTO attendance_photo(id,tenant_id,employee_id,time_log_id,content_type,photo_bytes,captured_by) VALUES(?,?,?,?,'image/jpeg',?,?)",photoId,user.tenantId(),ids.timekeepingId(),log.getId(),photo,user.id());
            jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,?,'time_log',?)",user.tenantId(),user.id(),"SELF_SERVICE_CLOCK_"+in.logType(),log.getId());
            return Map.of("id",log.getId(),"logType",log.getLogType(),"logTime",log.getLogTime(),"status",log.getStatus(),"photoCaptured",photoId!=null);
        });
    }
    @GetMapping(value="/attendance-photos/{photoId}")
    public ResponseEntity<byte[]> attendancePhoto(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID photoId) {
        authenticated(user);return tenants.inTenant(user.tenantId(),()->{
            UUID employeeId=employee(user).timekeepingId();
            List<byte[]> rows=jdbc.query("SELECT photo_bytes FROM attendance_photo WHERE tenant_id=? AND id=? AND employee_id=?",(rs,n)->rs.getBytes(1),user.tenantId(),photoId,employeeId);
            if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Attendance photo not found");
            return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(org.springframework.http.CacheControl.noStore()).body(rows.get(0));
        });
    }
    @PostMapping("/payslips/{payslipId}/acknowledge")
    @Transactional
    public Map<String,Object> acknowledgePayslip(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID payslipId) {
        authenticated(user); return tenants.inTenant(user.tenantId(),()->{
            UUID employeeId=employee(user).timekeepingId();
            int changed=jdbc.update("UPDATE payslip p SET acknowledged_at=coalesce(p.acknowledged_at,now()),updated_at=now() FROM payroll_run r WHERE p.tenant_id=? AND p.id=? AND p.employee_id=? AND r.tenant_id=p.tenant_id AND r.id=p.run_id AND r.status='EMPLOYEE_REVIEW'",user.tenantId(),payslipId,employeeId);
            if(changed==1) jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,'PAYSLIP_ACKNOWLEDGED','payslip',?)",user.tenantId(),user.id(),payslipId);
            if(changed==0) {
                Boolean already=jdbc.query("SELECT p.acknowledged_at IS NOT NULL FROM payslip p JOIN payroll_run r ON r.tenant_id=p.tenant_id AND r.id=p.run_id WHERE p.tenant_id=? AND p.id=? AND p.employee_id=? AND r.status IN ('LOCKED','PAID')",rs->rs.next()?rs.getBoolean(1):null,user.tenantId(),payslipId,employeeId);
                if(!Boolean.TRUE.equals(already)) throw new ResponseStatusException(HttpStatus.CONFLICT,"This payslip is not available for acknowledgement");
            }
            return Map.of("id",payslipId,"acknowledged",true);
        });
    }
    @GetMapping("/leave-types") public List<Map<String,Object>> leaveTypes(@AuthenticationPrincipal HrmUserPrincipal user) {
        authenticated(user); return tenants.inTenant(user.tenantId(),()->{
            UUID id=employee(user).timekeepingId();
            return jdbc.queryForList("SELECT t.id,t.code,t.name,coalesce(profile_rule.profile_name,'Default') AS profile_name,coalesce(profile_rule.employee_class,'GENERAL') AS employee_class,coalesce(profile_rule.paid,t.paid) AS paid,coalesce(profile_rule.annual_entitlement,t.annual_entitlement) AS annual_entitlement,coalesce(profile_rule.convertible,t.convertible) AS convertible,coalesce(profile_rule.carry_over_max,t.carry_over_max) AS carry_over_max,coalesce(profile_rule.hire_proration_enabled,t.hire_proration_enabled) AS hire_proration_enabled,coalesce(sum(l.days),0) AS balance FROM leave_type t LEFT JOIN leave_ledger l ON l.tenant_id=t.tenant_id AND l.leave_type_id=t.id AND l.employee_id=? AND l.entry_date<=current_date LEFT JOIN LATERAL (SELECT be.paid,be.annual_entitlement,be.convertible,be.carry_over_max,be.hire_proration_enabled,p.name AS profile_name,p.employee_class FROM employee_leave_benefit_assignment a JOIN leave_benefit_profile p ON p.tenant_id=a.tenant_id AND p.id=a.profile_id JOIN leave_benefit_entitlement be ON be.tenant_id=a.tenant_id AND be.profile_id=a.profile_id AND be.leave_type_id=t.id WHERE a.tenant_id=t.tenant_id AND a.employee_id=? AND a.effective_from<=current_date AND (a.effective_to IS NULL OR a.effective_to>=current_date) AND be.effective_from<=current_date AND (be.effective_to IS NULL OR be.effective_to>=current_date) ORDER BY a.effective_from DESC,be.effective_from DESC LIMIT 1) profile_rule ON true WHERE t.tenant_id=? GROUP BY t.id,profile_rule.profile_name,profile_rule.employee_class,profile_rule.paid,profile_rule.annual_entitlement,profile_rule.convertible,profile_rule.carry_over_max,profile_rule.hire_proration_enabled ORDER BY t.name",id,id,user.tenantId());
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
