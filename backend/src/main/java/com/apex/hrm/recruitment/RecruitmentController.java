package com.apex.hrm.recruitment;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/recruitment")
public class RecruitmentController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public RecruitmentController(JdbcTemplate jdbc, TenantJdbcExecutor tenants) { this.jdbc=jdbc; this.tenants=tenants; }

    public record JobInput(@NotNull UUID companyId,@NotBlank @Size(max=50) String code,@NotBlank @Size(max=180) String title,
        @Size(max=160) String department,@Size(max=160) String location,@NotBlank @Size(max=40) String employmentType,
        @Size(max=30000) String description,@Min(1) @Max(1000) int openings,LocalDate targetDate) {}
    public record CandidateInput(@NotBlank @Size(max=120) String firstName,@NotBlank @Size(max=120) String lastName,
        @NotBlank @Email @Size(max=254) String email,@Size(max=50) String phone,@Size(max=100) String source,
        @Size(max=2000) String resumeUrl,@Size(max=10000) String notes) {}
    public record ApplicationInput(@NotNull UUID candidateId,@NotNull UUID jobId) {}
    public record StageInput(@NotBlank @Pattern(regexp="NEW|SCREENING|INTERVIEW|ASSESSMENT|OFFER|HIRED|REJECTED|WITHDRAWN") String stage,
        @Min(1) @Max(5) Integer rating,@Size(max=2000) String dispositionReason) {}
    public record InterviewInput(@NotNull UUID applicationId,@NotNull UUID interviewerId,@NotNull OffsetDateTime scheduledAt,
        @Min(15) @Max(480) int durationMinutes,@NotBlank @Pattern(regexp="VIDEO|PHONE|ONSITE|OTHER") String interviewType,
        @Size(max=500) String location) {}
    public record FeedbackInput(@NotBlank @Size(max=10000) String feedback,@Min(1) @Max(5) Integer rating,
        @NotBlank @Pattern(regexp="SCHEDULED|COMPLETED|CANCELLED") String status) {}
    public record OfferInput(@NotBlank @Size(max=180) String offeredTitle,@NotNull @DecimalMin("0") BigDecimal annualSalary,
        @Pattern(regexp="[A-Z]{3}") String currency,LocalDate startDate,LocalDate expiresOn,@Size(max=10000) String notes) {}
    private void requireHr(HrmUserPrincipal user) {
        if(user==null || !user.isEnabled() || user.getAuthorities().stream().noneMatch(a->Set.of("ROLE_TENANT_ADMIN","ROLE_HR_ADMIN","ROLE_MFA_VERIFIED").contains(a.getAuthority()))
            || !user.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_MFA_VERIFIED"))
            || user.getAuthorities().stream().noneMatch(a->Set.of("ROLE_TENANT_ADMIN","ROLE_HR_ADMIN").contains(a.getAuthority())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Recruitment management requires an HR role and verified MFA");
    }
    private boolean exists(String sql,Object... args) { return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args)); }
    private void audit(HrmUserPrincipal user,String action,String entity,UUID id) {
        jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,?,?,?)",user.tenantId(),user.id(),action,entity,id);
    }
    @GetMapping("/jobs") public List<Map<String,Object>> jobs(@AuthenticationPrincipal HrmUserPrincipal user) {
        requireHr(user); return tenants.inTenant(user.tenantId(),()->jdbc.queryForList("SELECT j.*,c.legal_name AS company_name,(SELECT count(*) FROM recruitment_application a WHERE a.tenant_id=j.tenant_id AND a.job_id=j.id) AS applicant_count FROM recruitment_job j JOIN company c ON c.tenant_id=j.tenant_id AND c.id=j.company_id WHERE j.tenant_id=? ORDER BY j.updated_at DESC LIMIT 1000",user.tenantId()));
    }
    @PostMapping("/jobs") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> createJob(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody JobInput in) {
        requireHr(user); return tenants.inTenant(user.tenantId(),()->{ if(!exists("SELECT EXISTS(SELECT 1 FROM company WHERE tenant_id=? AND id=?)",user.tenantId(),in.companyId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Company is outside this workspace");
            UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO recruitment_job(id,tenant_id,company_id,code,title,department,location,employment_type,description,openings,target_date,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",id,user.tenantId(),in.companyId(),in.code().trim(),in.title().trim(),in.department(),in.location(),in.employmentType(),Objects.requireNonNullElse(in.description(),""),in.openings(),in.targetDate(),user.id()); audit(user,"RECRUITMENT_JOB_CREATED","recruitment_job",id); return Map.of("id",id,"status","DRAFT"); });
    }
    @PatchMapping("/jobs/{id}/status") public void jobStatus(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID id,@RequestBody Map<String,String> body) {
        requireHr(user); String status=body.get("status"); if(!Set.of("DRAFT","OPEN","ON_HOLD","CLOSED","CANCELLED").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid job status");
        tenants.inTenant(user.tenantId(),()->{ int n=jdbc.update("UPDATE recruitment_job SET status=?,updated_at=now() WHERE tenant_id=? AND id=?",status,user.tenantId(),id); if(n==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Job not found"); audit(user,"RECRUITMENT_JOB_"+status,"recruitment_job",id); return null; });
    }
    @GetMapping("/candidates") public List<Map<String,Object>> candidates(@AuthenticationPrincipal HrmUserPrincipal user) {
        requireHr(user); return tenants.inTenant(user.tenantId(),()->jdbc.queryForList("SELECT c.*,count(a.id) AS application_count FROM recruitment_candidate c LEFT JOIN recruitment_application a ON a.tenant_id=c.tenant_id AND a.candidate_id=c.id WHERE c.tenant_id=? GROUP BY c.tenant_id,c.id ORDER BY c.updated_at DESC LIMIT 2000",user.tenantId()));
    }
    @PostMapping("/candidates") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> createCandidate(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody CandidateInput in) {
        requireHr(user); return tenants.inTenant(user.tenantId(),()->{ UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO recruitment_candidate(id,tenant_id,first_name,last_name,email,phone,source,resume_url,notes,created_by) VALUES(?,?,?,?,?,?,?,?,?,?)",id,user.tenantId(),in.firstName().trim(),in.lastName().trim(),in.email().trim().toLowerCase(Locale.ROOT),in.phone(),in.source(),in.resumeUrl(),Objects.requireNonNullElse(in.notes(),""),user.id()); audit(user,"RECRUITMENT_CANDIDATE_CREATED","recruitment_candidate",id); return Map.of("id",id); });
    }
    @GetMapping("/applications") public List<Map<String,Object>> applications(@AuthenticationPrincipal HrmUserPrincipal user) {
        requireHr(user); return tenants.inTenant(user.tenantId(),()->jdbc.queryForList("SELECT a.id,a.job_id,a.candidate_id,a.stage,a.applied_on,a.rating,a.disposition_reason,a.owner_id,a.created_at,j.title AS job_title,j.code AS job_code,c.first_name,c.last_name,c.email,c.phone,c.source,(SELECT count(*) FROM recruitment_interview i WHERE i.tenant_id=a.tenant_id AND i.application_id=a.id) AS interview_count,(SELECT o.status FROM recruitment_offer o WHERE o.tenant_id=a.tenant_id AND o.application_id=a.id) AS offer_status FROM recruitment_application a JOIN recruitment_job j ON j.tenant_id=a.tenant_id AND j.id=a.job_id JOIN recruitment_candidate c ON c.tenant_id=a.tenant_id AND c.id=a.candidate_id WHERE a.tenant_id=? ORDER BY a.updated_at DESC LIMIT 2000",user.tenantId()));
    }
    @PostMapping("/applications") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> apply(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody ApplicationInput in) {
        requireHr(user); return tenants.inTenant(user.tenantId(),()->{ if(!exists("SELECT EXISTS(SELECT 1 FROM recruitment_job WHERE tenant_id=? AND id=? AND status='OPEN')",user.tenantId(),in.jobId())||!exists("SELECT EXISTS(SELECT 1 FROM recruitment_candidate WHERE tenant_id=? AND id=?)",user.tenantId(),in.candidateId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"An open job and workspace candidate are required"); UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO recruitment_application(id,tenant_id,job_id,candidate_id,created_by) VALUES(?,?,?,?,?)",id,user.tenantId(),in.jobId(),in.candidateId(),user.id()); jdbc.update("INSERT INTO recruitment_event(tenant_id,application_id,actor_id,event_type,to_stage) VALUES(?,?,?,'APPLICATION_CREATED','NEW')",user.tenantId(),id,user.id()); audit(user,"RECRUITMENT_APPLICATION_CREATED","recruitment_application",id); return Map.of("id",id,"stage","NEW"); });
    }
    @PatchMapping("/applications/{id}/stage") public void stage(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID id,@Valid @RequestBody StageInput in) {
        requireHr(user); tenants.inTenant(user.tenantId(),()->{ List<String> old=jdbc.query("SELECT stage FROM recruitment_application WHERE tenant_id=? AND id=? FOR UPDATE",(rs,n)->rs.getString(1),user.tenantId(),id); if(old.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Application not found"); jdbc.update("UPDATE recruitment_application SET stage=?,rating=?,disposition_reason=?,updated_at=now() WHERE tenant_id=? AND id=?",in.stage(),in.rating(),in.dispositionReason(),user.tenantId(),id); jdbc.update("INSERT INTO recruitment_event(tenant_id,application_id,actor_id,event_type,from_stage,to_stage,details) VALUES(?,?,?,'STAGE_CHANGED',?,?,?::jsonb)",user.tenantId(),id,user.id(),old.get(0),in.stage(),in.dispositionReason()==null?"{}":"{\"reason\":\""+in.dispositionReason().replace("\\","\\\\").replace("\"","\\\"")+"\"}"); audit(user,"RECRUITMENT_STAGE_CHANGED","recruitment_application",id); return null; });
    }
    @GetMapping("/applications/{id}/events") public List<Map<String,Object>> events(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID id) {
        requireHr(user); return tenants.inTenant(user.tenantId(),()->{ if(!exists("SELECT EXISTS(SELECT 1 FROM recruitment_application WHERE tenant_id=? AND id=?)",user.tenantId(),id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Application not found"); return jdbc.queryForList("SELECT e.id,e.event_type,e.from_stage,e.to_stage,e.details,e.created_at,a.email AS actor_email FROM recruitment_event e JOIN auth_identity a ON a.tenant_id=e.tenant_id AND a.id=e.actor_id WHERE e.tenant_id=? AND e.application_id=? ORDER BY e.created_at DESC",user.tenantId(),id); });
    }
    @GetMapping("/interviews") public List<Map<String,Object>> interviews(@AuthenticationPrincipal HrmUserPrincipal user) {
        requireHr(user); return tenants.inTenant(user.tenantId(),()->jdbc.queryForList("SELECT i.*,c.first_name,c.last_name,c.email,j.title AS job_title FROM recruitment_interview i JOIN recruitment_application a ON a.tenant_id=i.tenant_id AND a.id=i.application_id JOIN recruitment_candidate c ON c.tenant_id=a.tenant_id AND c.id=a.candidate_id JOIN recruitment_job j ON j.tenant_id=a.tenant_id AND j.id=a.job_id WHERE i.tenant_id=? ORDER BY i.scheduled_at DESC LIMIT 2000",user.tenantId()));
    }
    @PostMapping("/interviews") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> schedule(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody InterviewInput in) {
        requireHr(user); return tenants.inTenant(user.tenantId(),()->{ if(!exists("SELECT EXISTS(SELECT 1 FROM recruitment_application WHERE tenant_id=? AND id=?)",user.tenantId(),in.applicationId())||!exists("SELECT EXISTS(SELECT 1 FROM auth_identity WHERE tenant_id=? AND id=? AND enabled)",user.tenantId(),in.interviewerId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Application or interviewer not found in this workspace"); UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO recruitment_interview(id,tenant_id,application_id,interviewer_id,scheduled_at,duration_minutes,interview_type,location,created_by) VALUES(?,?,?,?,?,?,?,?,?)",id,user.tenantId(),in.applicationId(),in.interviewerId(),in.scheduledAt(),in.durationMinutes(),in.interviewType(),in.location(),user.id()); jdbc.update("INSERT INTO recruitment_event(tenant_id,application_id,actor_id,event_type,details) VALUES(?,?,?,'INTERVIEW_SCHEDULED',jsonb_build_object('interviewId',?::text,'scheduledAt',?::text))",user.tenantId(),in.applicationId(),user.id(),id,in.scheduledAt()); audit(user,"RECRUITMENT_INTERVIEW_SCHEDULED","recruitment_interview",id); return Map.of("id",id); });
    }
    @PatchMapping("/interviews/{id}/feedback") public void feedback(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID id,@Valid @RequestBody FeedbackInput in) {
        requireHr(user); tenants.inTenant(user.tenantId(),()->{ int n=jdbc.update("UPDATE recruitment_interview SET feedback=?,rating=?,status=?,updated_at=now() WHERE tenant_id=? AND id=?",in.feedback(),in.rating(),in.status(),user.tenantId(),id); if(n==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Interview not found"); audit(user,"RECRUITMENT_INTERVIEW_FEEDBACK","recruitment_interview",id); return null; });
    }
    @GetMapping("/offers") public List<Map<String,Object>> offers(@AuthenticationPrincipal HrmUserPrincipal user) {
        requireHr(user); return tenants.inTenant(user.tenantId(),()->jdbc.queryForList("SELECT o.*,c.first_name,c.last_name,c.email,j.title AS job_title FROM recruitment_offer o JOIN recruitment_application a ON a.tenant_id=o.tenant_id AND a.id=o.application_id JOIN recruitment_candidate c ON c.tenant_id=a.tenant_id AND c.id=a.candidate_id JOIN recruitment_job j ON j.tenant_id=a.tenant_id AND j.id=a.job_id WHERE o.tenant_id=? ORDER BY o.updated_at DESC LIMIT 1000",user.tenantId()));
    }
    @PostMapping("/offers") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> createOffer(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody OfferInput in,@RequestParam UUID applicationId) {
        requireHr(user); return tenants.inTenant(user.tenantId(),()->{ if(!exists("SELECT EXISTS(SELECT 1 FROM recruitment_application WHERE tenant_id=? AND id=? AND stage='OFFER')",user.tenantId(),applicationId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Application must be in OFFER stage"); UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO recruitment_offer(id,tenant_id,application_id,offered_title,annual_salary,currency,start_date,expires_on,notes,created_by) VALUES(?,?,?,?,?,?,?,?,?,?)",id,user.tenantId(),applicationId,in.offeredTitle(),in.annualSalary(),Objects.requireNonNullElse(in.currency(),"PHP"),in.startDate(),in.expiresOn(),Objects.requireNonNullElse(in.notes(),""),user.id()); jdbc.update("INSERT INTO recruitment_event(tenant_id,application_id,actor_id,event_type,details) VALUES(?,?,?,'OFFER_CREATED',jsonb_build_object('offerId',?::text))",user.tenantId(),applicationId,user.id(),id); audit(user,"RECRUITMENT_OFFER_CREATED","recruitment_offer",id); return Map.of("id",id,"status","DRAFT"); });
    }
    @PatchMapping("/offers/{id}/status") public void offerStatus(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID id,@RequestBody Map<String,String> body) {
        requireHr(user); String status=body.get("status"); if(!Set.of("DRAFT","SENT","ACCEPTED","DECLINED","EXPIRED","WITHDRAWN").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid offer status"); tenants.inTenant(user.tenantId(),()->{ int n=jdbc.update("UPDATE recruitment_offer SET status=?,updated_at=now() WHERE tenant_id=? AND id=?",status,user.tenantId(),id); if(n==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Offer not found"); if(status.equals("ACCEPTED")) jdbc.update("UPDATE recruitment_application SET stage='HIRED',updated_at=now() WHERE tenant_id=? AND id=(SELECT application_id FROM recruitment_offer WHERE tenant_id=? AND id=?)",user.tenantId(),user.tenantId(),id); audit(user,"RECRUITMENT_OFFER_"+status,"recruitment_offer",id); return null; });
    }
}
