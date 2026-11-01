package com.apex.hrm.learning;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** M6: Tenant-isolated training catalog, learning records, skills and credentials. */
@RestController
@RequestMapping("/api/v1/learning")
public class LearningController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public LearningController(JdbcTemplate jdbc, TenantJdbcExecutor tenants) {
        this.jdbc = jdbc; this.tenants = tenants;
    }
    public record CourseInput(@NotNull UUID companyId, @NotBlank @Size(max=50) String code,
        @NotBlank @Size(max=200) String title, @Size(max=5000) String description,
        @NotNull String deliveryMode, @NotNull @DecimalMin("0") java.math.BigDecimal durationHours) {}
    public record EnrollmentInput(@NotNull UUID courseId, @NotNull UUID employeeId) {}
    public record CompletionInput(@NotNull String status, @DecimalMin("0") @DecimalMax("100") java.math.BigDecimal score) {}
    public record SkillInput(@NotBlank @Size(max=120) String name, @Size(max=100) String category) {}
    public record EmployeeSkillInput(@NotNull UUID employeeId, @NotNull UUID skillId, @Min(1) @Max(5) int level) {}
    public record CertificationInput(@NotNull UUID employeeId, UUID courseId,
        @NotBlank @Size(max=200) String title, @NotBlank @Size(max=160) String issuer,
        @Size(max=160) String credentialRef, @NotNull LocalDate issuedOn, LocalDate expiresOn) {}

    private void audit(HrmUserPrincipal user, String action, String kind, UUID id) {
        jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,?,?,?)",
            user.tenantId(),user.id(),action,kind,id);
    }
    private boolean exists(String sql,Object... args) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args));
    }
    private void mustExist(boolean found) {
        if (!found) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Record not found in this workspace");
    }
    /** Named picker endpoints keep company and employee selection within the learning boundary. */
    @GetMapping("/companies")
    public List<Map<String,Object>> companies(@AuthenticationPrincipal HrmUserPrincipal user) {
        return tenants.inTenant(user.tenantId(),()->jdbc.queryForList(
            "SELECT id,legal_name AS \"name\" FROM company WHERE tenant_id=? ORDER BY legal_name LIMIT 500",
            user.tenantId()));
    }
    @GetMapping("/employees")
    public List<Map<String,Object>> employees(@AuthenticationPrincipal HrmUserPrincipal user,@RequestParam UUID companyId) {
        return tenants.inTenant(user.tenantId(),()->jdbc.queryForList(
            "SELECT id,first_name || ' ' || last_name AS \"name\",employee_number AS \"employeeNumber\" FROM personnel_employee WHERE tenant_id=? AND company_id=? ORDER BY last_name,first_name LIMIT 500",
            user.tenantId(),companyId));
    }
    @GetMapping("/courses")
    public List<Map<String,Object>> courses(@AuthenticationPrincipal HrmUserPrincipal user) {
        return tenants.inTenant(user.tenantId(),()->jdbc.queryForList(
            "SELECT id,company_id AS \"companyId\",code,title,description,delivery_mode AS \"deliveryMode\",duration_hours AS \"durationHours\",active FROM ld_course WHERE tenant_id=? ORDER BY title LIMIT 500",
            user.tenantId()));
    }
    @PostMapping("/courses")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> course(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody CourseInput in) {
        return tenants.inTenant(user.tenantId(),()->{
            mustExist(exists("SELECT EXISTS(SELECT 1 FROM company WHERE tenant_id=? AND id=?)",user.tenantId(),in.companyId()));
            if (!List.of("ONLINE","CLASSROOM","BLENDED","SELF_PACED").contains(in.deliveryMode()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid delivery mode");
            UUID id=jdbc.queryForObject("INSERT INTO ld_course(tenant_id,company_id,code,title,description,delivery_mode,duration_hours) VALUES(?,?,?,?,?,?,?) RETURNING id",
                UUID.class,user.tenantId(),in.companyId(),in.code().trim(),in.title().trim(),in.description()==null?"":in.description().trim(),in.deliveryMode(),in.durationHours());
            audit(user,"LD_COURSE_CREATED","ld_course",id);
            return Map.of("id",id);
        });
    }
    @GetMapping("/enrollments")
    public List<Map<String,Object>> enrollments(@AuthenticationPrincipal HrmUserPrincipal user) {
        return tenants.inTenant(user.tenantId(),()->jdbc.queryForList(
            "SELECT e.id,e.course_id AS \"courseId\",c.title AS \"courseTitle\",e.employee_id AS \"employeeId\",p.first_name || ' ' || p.last_name AS \"employeeName\",e.status,e.enrolled_on AS \"enrolledOn\",e.completed_on AS \"completedOn\",e.score FROM ld_enrollment e JOIN ld_course c ON c.tenant_id=e.tenant_id AND c.id=e.course_id JOIN personnel_employee p ON p.tenant_id=e.tenant_id AND p.id=e.employee_id WHERE e.tenant_id=? ORDER BY e.enrolled_on DESC LIMIT 500",
            user.tenantId()));
    }
    @PostMapping("/enrollments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> enroll(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody EnrollmentInput in) {
        return tenants.inTenant(user.tenantId(),()->{
            mustExist(exists("SELECT EXISTS(SELECT 1 FROM ld_course c JOIN personnel_employee p ON p.tenant_id=c.tenant_id AND p.company_id=c.company_id WHERE c.tenant_id=? AND c.id=? AND p.id=? AND c.active=true AND p.employment_status='ACTIVE')",
                user.tenantId(),in.courseId(),in.employeeId()));
            UUID id=jdbc.queryForObject("INSERT INTO ld_enrollment(tenant_id,course_id,employee_id) VALUES(?,?,?) RETURNING id",
                UUID.class,user.tenantId(),in.courseId(),in.employeeId());
            audit(user,"LD_EMPLOYEE_ENROLLED","ld_enrollment",id);
            return Map.of("id",id);
        });
    }
    @PatchMapping("/enrollments/{id}")
    public Map<String,Object> updateEnrollment(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID id,
        @Valid @RequestBody CompletionInput in) {
        return tenants.inTenant(user.tenantId(),()->{
            if (!List.of("ENROLLED","IN_PROGRESS","COMPLETED","CANCELLED").contains(in.status()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid enrollment status");
            // One-way transitions: ENROLLED -> IN_PROGRESS/CANCELLED -> COMPLETED/CANCELLED.
            // A completed or cancelled record cannot silently be reopened or overwritten.
            if ("ENROLLED".equals(in.status()) || (!"COMPLETED".equals(in.status()) && in.score()!=null))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid transition or score");
            int changed=jdbc.update("""
                UPDATE ld_enrollment SET status=?,
                    completed_on=CASE WHEN ?='COMPLETED' THEN current_date ELSE NULL END,
                    score=CASE WHEN ?='COMPLETED' THEN CAST(? AS numeric(5,2)) ELSE NULL END
                WHERE tenant_id=? AND id=? AND
                  ((status='ENROLLED' AND ? IN ('IN_PROGRESS','CANCELLED'))
                    OR (status='IN_PROGRESS' AND ? IN ('COMPLETED','CANCELLED')))
                """,in.status(),in.status(),in.status(),in.score(),user.tenantId(),id,in.status(),in.status());
            if(changed!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Enrollment not found or transition not allowed");
            audit(user,"LD_ENROLLMENT_UPDATED","ld_enrollment",id);
            return Map.of("id",id,"status",in.status());
        });
    }
    @GetMapping("/skills")
    public List<Map<String,Object>> skills(@AuthenticationPrincipal HrmUserPrincipal user) {
        return tenants.inTenant(user.tenantId(),()->jdbc.queryForList(
            "SELECT id,name,category FROM ld_skill WHERE tenant_id=? ORDER BY name LIMIT 500",user.tenantId()));
    }
    @PostMapping("/skills")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> skill(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody SkillInput in) {
        return tenants.inTenant(user.tenantId(),()->{
            UUID id=jdbc.queryForObject("INSERT INTO ld_skill(tenant_id,name,category) VALUES(?,?,?) RETURNING id",UUID.class,
                user.tenantId(),in.name().trim(),in.category());
            audit(user,"LD_SKILL_CREATED","ld_skill",id);return Map.of("id",id);
        });
    }
    @GetMapping("/employee-skills")
    public List<Map<String,Object>> employeeSkills(@AuthenticationPrincipal HrmUserPrincipal user) {
        return tenants.inTenant(user.tenantId(),()->jdbc.queryForList(
            "SELECT es.id,es.employee_id AS \"employeeId\",es.skill_id AS \"skillId\",s.name AS \"skillName\",es.level,es.assessed_on AS \"assessedOn\" FROM ld_employee_skill es JOIN ld_skill s ON s.tenant_id=es.tenant_id AND s.id=es.skill_id WHERE es.tenant_id=? ORDER BY es.assessed_on DESC LIMIT 500",user.tenantId()));
    }
    @PostMapping("/employee-skills")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> assignSkill(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody EmployeeSkillInput in) {
        return tenants.inTenant(user.tenantId(),()->{
            mustExist(exists("SELECT EXISTS(SELECT 1 FROM personnel_employee WHERE tenant_id=? AND id=?)",user.tenantId(),in.employeeId()));
            mustExist(exists("SELECT EXISTS(SELECT 1 FROM ld_skill WHERE tenant_id=? AND id=?)",user.tenantId(),in.skillId()));
            UUID id=jdbc.queryForObject("INSERT INTO ld_employee_skill(tenant_id,employee_id,skill_id,level) VALUES(?,?,?,?) ON CONFLICT(tenant_id,employee_id,skill_id) DO UPDATE SET level=EXCLUDED.level,assessed_on=current_date RETURNING id",
                UUID.class,user.tenantId(),in.employeeId(),in.skillId(),in.level());
            audit(user,"LD_EMPLOYEE_SKILL_ASSESSED","ld_employee_skill",id);return Map.of("id",id);
        });
    }
    @GetMapping("/certifications")
    public List<Map<String,Object>> certifications(@AuthenticationPrincipal HrmUserPrincipal user) {
        return tenants.inTenant(user.tenantId(),()->jdbc.queryForList(
            "SELECT id,employee_id AS \"employeeId\",course_id AS \"courseId\",title,issuer,credential_ref AS \"credentialRef\",issued_on AS \"issuedOn\",expires_on AS \"expiresOn\" FROM ld_certification WHERE tenant_id=? ORDER BY issued_on DESC LIMIT 500",user.tenantId()));
    }
    public record CertificationRenewal(@NotNull LocalDate issuedOn,LocalDate expiresOn,
        @Size(max=160) String credentialRef) {}

    @PatchMapping("/certifications/{id}/renew")
    public Map<String,Object> renew(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID id,
        @Valid @RequestBody CertificationRenewal in) {
        return tenants.inTenant(user.tenantId(),()->{
            if(in.expiresOn()!=null && in.expiresOn().isBefore(in.issuedOn()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Expiry must follow issue date");
            int updated=jdbc.update("""
                UPDATE ld_certification SET issued_on=?,expires_on=?,credential_ref=?
                WHERE tenant_id=? AND id=? AND issued_on<=?
                """,in.issuedOn(),in.expiresOn(),in.credentialRef(),user.tenantId(),id,in.issuedOn());
            if(updated!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Certification not found or renewal date predates existing issue date");
            audit(user,"LD_CERTIFICATION_RENEWED","ld_certification",id);
            return Map.of("id",id);
        });
    }

    @PostMapping("/certifications")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> certificate(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody CertificationInput in) {
        return tenants.inTenant(user.tenantId(),()->{
            mustExist(exists("SELECT EXISTS(SELECT 1 FROM personnel_employee WHERE tenant_id=? AND id=?)",user.tenantId(),in.employeeId()));
            if(in.courseId()!=null) mustExist(exists("SELECT EXISTS(SELECT 1 FROM ld_course c JOIN personnel_employee p ON p.tenant_id=c.tenant_id AND p.company_id=c.company_id WHERE c.tenant_id=? AND c.id=? AND p.id=?)",user.tenantId(),in.courseId(),in.employeeId()));
            if(in.expiresOn()!=null && in.expiresOn().isBefore(in.issuedOn()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Expiry must follow issue date");
            UUID id=jdbc.queryForObject("INSERT INTO ld_certification(tenant_id,employee_id,course_id,title,issuer,credential_ref,issued_on,expires_on) VALUES(?,?,?,?,?,?,?,?) RETURNING id",
                UUID.class,user.tenantId(),in.employeeId(),in.courseId(),in.title().trim(),in.issuer().trim(),in.credentialRef(),in.issuedOn(),in.expiresOn());
            audit(user,"LD_CERTIFICATION_RECORDED","ld_certification",id);return Map.of("id",id);
        });
    }
}
