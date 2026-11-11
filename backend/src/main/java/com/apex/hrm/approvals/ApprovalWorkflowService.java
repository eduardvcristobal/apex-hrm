package com.apex.hrm.approvals;

import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import com.apex.hrm.timekeeping.service.AttendanceService;
import com.apex.hrm.timekeeping.service.LeaveService;
import com.apex.hrm.timekeeping.service.OvertimeService;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApprovalWorkflowService {
    private final JdbcTemplate jdbc; private final TenantJdbcExecutor tenants;
    private final LeaveService leaves; private final OvertimeService overtime; private final AttendanceService attendance;
    public ApprovalWorkflowService(JdbcTemplate jdbc,TenantJdbcExecutor tenants,LeaveService leaves,OvertimeService overtime,AttendanceService attendance){this.jdbc=jdbc;this.tenants=tenants;this.leaves=leaves;this.overtime=overtime;this.attendance=attendance;}
    public record Step(int stepNo,String label,String actionType,String approverType,String roleCode,UUID identityId,Integer managerDepth){}
    public record Assignee(UUID identityId){}
    private ResponseStatusException fail(HttpStatus s,String m){return new ResponseStatusException(s,m);}
    public UUID start(UUID tenant,UUID actor,UUID employee,String process,String entity,UUID businessId,String summary){return tenants.inTenant(tenant,()->{
        List<Map<String,Object>> employeeRows=jdbc.queryForList("SELECT company_id,department_id FROM personnel_employee WHERE tenant_id=? AND id=? AND employment_status IN ('ACTIVE','ON_LEAVE')",tenant,employee);
        if(employeeRows.isEmpty())throw fail(HttpStatus.NOT_FOUND,"Active requester employee was not found");
        Map<String,Object> e=employeeRows.get(0); UUID company=(UUID)e.get("company_id"),dept=(UUID)e.get("department_id");
        List<UUID> versions=jdbc.query("""
SELECT workflow_version_id FROM approval_assignment WHERE tenant_id=? AND process_code=? AND active AND (company_id IS NULL OR company_id=?) AND (department_id IS NULL OR department_id=?) ORDER BY (department_id IS NOT NULL) DESC,(company_id IS NOT NULL) DESC,created_at DESC LIMIT 1""",(rs,n)->rs.getObject(1,UUID.class),tenant,process,company,dept);
        if(versions.isEmpty())throw fail(HttpStatus.UNPROCESSABLE_ENTITY,"No published approval workflow is assigned for this request. Contact your workspace administrator.");
        UUID version=versions.get(0); UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO approval_instance(id,tenant_id,process_code,business_entity,business_id,workflow_version_id,requester_identity_id,requester_employee_id,company_id,department_id,summary) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,tenant,process,entity,businessId,version,actor,employee,company,dept,summary);
        event(tenant,id,actor,"REQUEST_SUBMITTED",1,Map.of("processCode",process,"summary",summary));
        List<Assignee> assignees=resolve(tenant,version,1,employee);
        if(assignees.isEmpty())throw fail(HttpStatus.UNPROCESSABLE_ENTITY,"Approval step 1 has no eligible approver. The request was not submitted.");
        assign(tenant,id,1,actor,assignees); return id;
    });}
    private List<Assignee> resolve(UUID tenant,UUID version,int stepNo,UUID requesterEmployee){
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT approver_type,role_code,identity_id,manager_depth FROM approval_workflow_step WHERE tenant_id=? AND workflow_version_id=? AND step_no=?",tenant,version,stepNo);
        if(rows.isEmpty())return List.of(); Map<String,Object> step=rows.get(0); String type=(String)step.get("approver_type");
        List<UUID> ids=new ArrayList<>();
        if("USER".equals(type)){ids.add((UUID)step.get("identity_id"));}
        else if("ROLE".equals(type)){String role=(String)step.get("role_code");ids=jdbc.query("SELECT i.id FROM auth_identity i JOIN auth_identity_role r ON r.identity_id=i.id WHERE i.tenant_id=? AND i.enabled AND r.role=? ORDER BY i.created_at,i.id",(rs,n)->rs.getObject(1,UUID.class),tenant,role);}
        else {int depth="REPORTING_MANAGER".equals(type)?1:(Integer)step.get("manager_depth");List<UUID> managers=jdbc.query("""
WITH RECURSIVE chain(employee_id,supervisor_id,depth) AS (SELECT id,supervisor_id,1 FROM personnel_employee WHERE tenant_id=? AND id=? UNION ALL SELECT e.id,e.supervisor_id,c.depth+1 FROM personnel_employee e JOIN chain c ON e.id=c.supervisor_id WHERE e.tenant_id=? AND c.depth<10) SELECT supervisor_id FROM chain WHERE depth=? AND supervisor_id IS NOT NULL""",(rs,n)->rs.getObject(1,UUID.class),tenant,requesterEmployee,tenant,depth);if(!managers.isEmpty()){UUID manager=managers.get(0);ids=jdbc.query("SELECT identity_id FROM ld_employee_account WHERE tenant_id=? AND employee_id=? UNION ALL SELECT p.identity_id FROM performance_employee_identity p WHERE p.tenant_id=? AND p.employee_id=? AND NOT EXISTS(SELECT 1 FROM ld_employee_account l WHERE l.tenant_id=p.tenant_id AND l.identity_id=p.identity_id)",(rs,n)->rs.getObject(1,UUID.class),tenant,manager,tenant,manager);}}
        return ids.stream().filter(id->id!=null).distinct().map(Assignee::new).toList();
    }
    private void assign(UUID tenant,UUID instance,int step,UUID requester,List<Assignee> assignees){int inserted=0;for(Assignee a:assignees){if(a.identityId().equals(requester))continue;inserted+=jdbc.update("INSERT INTO approval_task(tenant_id,instance_id,step_no,assigned_to) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",tenant,instance,step,a.identityId());}if(inserted==0)throw fail(HttpStatus.UNPROCESSABLE_ENTITY,"Approval routing resolved only to the requester. Configure a different approver.");}
    private void event(UUID tenant,UUID instance,UUID actor,String type,Integer step,Map<String,?> details){jdbc.update("INSERT INTO approval_event(tenant_id,instance_id,actor_id,event_type,step_no,details) VALUES(?,?,?,?,?,?::jsonb)",tenant,instance,actor,type,step,new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(details).toString());}
    public List<Map<String,Object>> inbox(UUID tenant,UUID actor){return tenants.inTenant(tenant,()->jdbc.queryForList("""
SELECT t.id AS task_id,t.step_no,t.assigned_at,i.id AS instance_id,i.process_code,i.business_entity,i.business_id,i.summary,i.requester_identity_id,i.status AS instance_status,s.label,s.action_type,p.email AS requester_email,e.first_name,e.last_name,e.employee_number FROM approval_task t JOIN approval_instance i ON i.tenant_id=t.tenant_id AND i.id=t.instance_id JOIN approval_workflow_step s ON s.tenant_id=i.tenant_id AND s.workflow_version_id=i.workflow_version_id AND s.step_no=t.step_no JOIN auth_identity p ON p.tenant_id=i.tenant_id AND p.id=i.requester_identity_id JOIN personnel_employee e ON e.tenant_id=i.tenant_id AND e.id=i.requester_employee_id WHERE t.tenant_id=? AND t.assigned_to=? AND t.status='PENDING' AND i.status='PENDING' ORDER BY t.assigned_at,i.created_at""",tenant,actor));}
    public List<Map<String,Object>> myRequests(UUID tenant,UUID actor){return tenants.inTenant(tenant,()->jdbc.queryForList("SELECT i.id,i.process_code,i.business_entity,i.business_id,i.summary,i.status,i.current_step,i.created_at,i.completed_at FROM approval_instance i WHERE i.tenant_id=? AND i.requester_identity_id=? ORDER BY i.created_at DESC LIMIT 500",tenant,actor));}
    public List<Map<String,Object>> history(UUID tenant,UUID actor,UUID instance){return tenants.inTenant(tenant,()->{Boolean allowed=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM approval_instance WHERE tenant_id=? AND id=? AND requester_identity_id=?) OR EXISTS(SELECT 1 FROM approval_task WHERE tenant_id=? AND instance_id=? AND assigned_to=?)",Boolean.class,tenant,instance,actor,tenant,instance,actor);if(!Boolean.TRUE.equals(allowed))throw fail(HttpStatus.NOT_FOUND,"Approval request not found");return jdbc.queryForList("SELECT ev.id,ev.event_type,ev.step_no,ev.details,ev.created_at,a.email AS actor_email FROM approval_event ev LEFT JOIN auth_identity a ON a.tenant_id=ev.tenant_id AND a.id=ev.actor_id WHERE ev.tenant_id=? AND ev.instance_id=? ORDER BY ev.created_at,ev.id",tenant,instance);});}
    public Map<String,Object> decide(UUID tenant,UUID actor,UUID taskId,String decision,String comments,Integer approvedMinutes){
        final Integer requestedApprovedMinutes = approvedMinutes;
        final String decisionComments = comments;
        return tenants.inTenant(tenant,()->{
        List<Map<String,Object>> taskRows=jdbc.queryForList("""
SELECT t.instance_id,t.step_no,t.status,i.process_code,i.business_id,i.requester_identity_id,i.requester_employee_id,i.status AS instance_status FROM approval_task t JOIN approval_instance i ON i.tenant_id=t.tenant_id AND i.id=t.instance_id WHERE t.tenant_id=? AND t.id=? AND t.assigned_to=? FOR UPDATE OF t,i""",tenant,taskId,actor);
        if(taskRows.isEmpty())throw fail(HttpStatus.NOT_FOUND,"Approval task not found");Map<String,Object> t=taskRows.get(0);
        if(!"PENDING".equals(t.get("status"))||!"PENDING".equals(t.get("instance_status")))throw fail(HttpStatus.CONFLICT,"This approval task is no longer pending");
        if(actor.equals(t.get("requester_identity_id")))throw fail(HttpStatus.FORBIDDEN,"Requesters cannot approve their own requests");
        UUID instance=(UUID)t.get("instance_id"),business=(UUID)t.get("business_id");int step=((Number)t.get("step_no")).intValue();String process=(String)t.get("process_code");
        jdbc.update("UPDATE approval_task SET status=?,decided_at=now() WHERE tenant_id=? AND id=?",decision.equals("APPROVE")?"APPROVED":"REJECTED",tenant,taskId);
        Integer effectiveApprovedMinutes = requestedApprovedMinutes;
        String effectiveComments = decisionComments;
        if ("OVERTIME".equals(process) && "APPROVE".equals(decision)) {
            Integer requested = jdbc.queryForObject("SELECT minutes_requested FROM overtime_request WHERE tenant_id=? AND id=?", Integer.class, tenant, business);
            if (effectiveApprovedMinutes == null) effectiveApprovedMinutes = requested;
            if (effectiveApprovedMinutes == null || effectiveApprovedMinutes < 1 || requested == null || effectiveApprovedMinutes > requested)
                throw fail(HttpStatus.BAD_REQUEST, "Approved overtime minutes must be between 1 and the requested minutes");
            effectiveComments = Objects.requireNonNullElse(effectiveComments, "") + "\nApproved minutes: " + effectiveApprovedMinutes;
        }
        jdbc.update("INSERT INTO approval_decision(tenant_id,task_id,decided_by,decision,comments) VALUES(?,?,?,?,?)",tenant,taskId,actor,decision,Objects.requireNonNullElse(effectiveComments,""));
        jdbc.update("UPDATE approval_task SET status='SUPERSEDED' WHERE tenant_id=? AND instance_id=? AND step_no=? AND status='PENDING'",tenant,instance,step);
        event(tenant,instance,actor,"STEP_"+decision,step,Map.of("comments",Objects.requireNonNullElse(effectiveComments,"")));
        if("REJECT".equals(decision)){finishBusiness(tenant,actor,process,business,"REJECT",effectiveApprovedMinutes);jdbc.update("UPDATE approval_instance SET status='REJECTED',completed_at=now() WHERE tenant_id=? AND id=?",tenant,instance);event(tenant,instance,actor,"REQUEST_REJECTED",step,Map.of());return Map.of("instanceId",instance,"status","REJECTED");}
        List<Integer> next=jdbc.query("SELECT step_no FROM approval_workflow_step WHERE tenant_id=? AND workflow_version_id=(SELECT workflow_version_id FROM approval_instance WHERE tenant_id=? AND id=?) AND step_no>? ORDER BY step_no LIMIT 1",(rs,n)->rs.getInt(1),tenant,tenant,instance,step);
        if(next.isEmpty()){
            if ("OVERTIME".equals(process)) effectiveApprovedMinutes = jdbc.queryForList("SELECT comments FROM approval_decision d JOIN approval_task t ON t.tenant_id=d.tenant_id AND t.id=d.task_id WHERE t.tenant_id=? AND t.instance_id=? AND d.decision='APPROVE' ORDER BY t.step_no", String.class, tenant, instance)
                    .stream().map(value -> { java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("Approved minutes: (\\d+)").matcher(value); return matcher.find()?Integer.valueOf(matcher.group(1)):null; })
                    .filter(Objects::nonNull).min(Integer::compareTo).orElse(effectiveApprovedMinutes);
            finishBusiness(tenant,actor,process,business,"APPROVE",effectiveApprovedMinutes);jdbc.update("UPDATE approval_instance SET status='APPROVED',completed_at=now() WHERE tenant_id=? AND id=?",tenant,instance);event(tenant,instance,actor,"REQUEST_APPROVED",step,Map.of());return Map.of("instanceId",instance,"status","APPROVED");}
        int nextStep=next.get(0); UUID requesterEmployee=(UUID)t.get("requester_employee_id");List<Assignee> assignees=resolve(tenant,(UUID)jdbc.queryForObject("SELECT workflow_version_id FROM approval_instance WHERE tenant_id=? AND id=?",UUID.class,tenant,instance),nextStep,requesterEmployee);
        if(assignees.isEmpty()){jdbc.update("UPDATE approval_instance SET status='ROUTING_ERROR' WHERE tenant_id=? AND id=?",tenant,instance);event(tenant,instance,actor,"ROUTING_ERROR",nextStep,Map.of("message","No eligible approver resolved"));return Map.of("instanceId",instance,"status","ROUTING_ERROR");}
        jdbc.update("UPDATE approval_instance SET current_step=? WHERE tenant_id=? AND id=?",nextStep,tenant,instance);assign(tenant,instance,nextStep,(UUID)t.get("requester_identity_id"),assignees);event(tenant,instance,actor,"STEP_ASSIGNED",nextStep,Map.of());return Map.of("instanceId",instance,"status","PENDING","currentStep",nextStep);
    });}
    private void finishBusiness(UUID tenant,UUID actor,String process,UUID business,String outcome,Integer approvedMinutes){switch(process){case "LEAVE"->{if("APPROVE".equals(outcome))leaves.approve(business,actor);else leaves.reject(business,actor);}case "OVERTIME"->{if("APPROVE".equals(outcome))overtime.approve(business,approvedMinutes,actor);else overtime.reject(business,actor);}case "ATTENDANCE_CORRECTION"->{if("APPROVE".equals(outcome))attendance.approveCorrection(business,actor);else attendance.rejectCorrection(business,actor);}case "HEADCOUNT"->{if("REJECT".equals(outcome)){jdbc.update("UPDATE recruitment_headcount_request SET status='REJECTED',updated_at=now() WHERE tenant_id=? AND id=?",tenant,business);return;}List<Map<String,Object>> rows=jdbc.queryForList("SELECT r.company_id,r.department_id,r.role_title,r.employment_type,r.positions,r.target_date,r.business_justification,d.department_name FROM recruitment_headcount_request r JOIN personnel_department d ON d.tenant_id=r.tenant_id AND d.id=r.department_id WHERE r.tenant_id=? AND r.id=? AND r.status='PENDING' FOR UPDATE OF r",tenant,business);if(rows.isEmpty())throw fail(HttpStatus.CONFLICT,"Headcount request is no longer pending");Map<String,Object> r=rows.get(0);UUID job=UUID.randomUUID();String code="HC-"+business.toString().replace("-","").substring(0,12).toUpperCase(Locale.ROOT);jdbc.update("INSERT INTO recruitment_job(id,tenant_id,company_id,code,title,department,employment_type,description,status,openings,target_date,created_by) VALUES(?,?,?,?,?,?,?,?,'OPEN',?,?,?)",job,tenant,r.get("company_id"),code,r.get("role_title"),r.get("department_name"),r.get("employment_type"),"Authorized headcount request: "+r.get("business_justification"),r.get("positions"),r.get("target_date"),actor);jdbc.update("UPDATE recruitment_headcount_request SET status='APPROVED',approved_job_id=?,approved_at=now(),updated_at=now() WHERE tenant_id=? AND id=?",job,tenant,business);}default->throw fail(HttpStatus.UNPROCESSABLE_ENTITY,"No finalization handler is registered for "+process);}}
}
