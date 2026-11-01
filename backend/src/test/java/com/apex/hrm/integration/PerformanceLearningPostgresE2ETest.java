package com.apex.hrm.integration;

import com.apex.hrm.common.TenantContext;
import com.apex.hrm.learning.LearningController;
import com.apex.hrm.performance.PerformanceController;
import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Disposable PostgreSQL 16 M7.5 integration: real M5/M6 controller logic,
 * tenant-bound transaction SQL, FK and workflow constraints, and persistence.
 * This is intentionally skipped outside the dedicated HRM_TEST_DB_URL gate.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=${HRM_TEST_DB_URL:jdbc:postgresql://localhost:5432/apex_hrm_m75_cert}",
    "spring.datasource.username=${HRM_TEST_DB_USER:hrm}",
    "spring.datasource.password=${HRM_TEST_DB_PASSWORD:}"
})
@EnabledIfEnvironmentVariable(named="HRM_TEST_DB_URL", matches=".+")
class PerformanceLearningPostgresE2ETest {
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantJdbcExecutor tenants;
    @Autowired PerformanceController performance;
    @Autowired LearningController learning;

    @AfterEach void clear() { TenantContext.clear(); }

    private record Fixture(UUID tenant, UUID company, UUID employee, HrmUserPrincipal user) {}

    private Fixture createFixture() {
        UUID tenant = UUID.randomUUID(), company = UUID.randomUUID(), employee = UUID.randomUUID(),
             identity = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant(id,code,name,slug,display_name) VALUES(?,?,?,?,?)",
            tenant,"M75-"+tenant.toString().substring(0,8),"M75 synthetic",
            "m75-"+tenant,"M75 synthetic");
        jdbc.update("INSERT INTO company(id,tenant_id,legal_name,company_code) VALUES(?,?,?,?)",
            company,tenant,"M75 synthetic company","M75");
        jdbc.update("INSERT INTO auth_identity(id,tenant_id,email,password_hash,enabled) VALUES(?,?,?,?,true)",
            identity,tenant,identity+"@example.invalid","disabled-for-ci");
        tenants.inTenant(tenant,()->{
            jdbc.update("""
                INSERT INTO personnel_employee(tenant_id,id,company_id,employee_number,
                 first_name,last_name,hire_date,employment_status)
                VALUES(?,?,?,?,?,?,?,'ACTIVE')
                """,tenant,employee,company,"M75-"+employee.toString().substring(0,8),
                "Synthetic","Employee",java.sql.Date.valueOf(LocalDate.of(2025,1,1)));
            return null;
        });
        return new Fixture(tenant,company,employee,new HrmUserPrincipal(identity,tenant,
            "hr@example.invalid","",true,List.of(
                new SimpleGrantedAuthority("ROLE_HR_ADMIN"),
                new SimpleGrantedAuthority("ROLE_MFA_VERIFIED"))));
    }

    @Test
    void fullPerformanceAndLearningLifecyclePersistsAndIsTenantIsolated() {
        Fixture a = createFixture(), b = createFixture();
        TenantContext.set(a.tenant());
        var cycle = performance.createCycle(a.user(),
            new PerformanceController.CycleInput(a.company(),"Annual cycle",
                LocalDate.of(2026,1,1),LocalDate.of(2026,12,31)));
        UUID cycleId=(UUID)cycle.get("id");
        performance.activate(a.user(),cycleId);
        var goal = performance.createGoal(a.user(),new PerformanceController.GoalInput(
            cycleId,a.employee(),"Safety adherence","Quarterly KPI","Score",
            new BigDecimal("100"),new BigDecimal("80"),new BigDecimal("50"),
            LocalDate.of(2026,11,30)));
        assertEquals("ACTIVE",goal.get("status"));
        var score=performance.scores(a.user(),cycleId);
        assertEquals(1,score.size());
        assertEquals(0,new BigDecimal("80").compareTo((BigDecimal)score.get(0).get("attainmentPercent")));
        var review=performance.createReview(a.user(),new PerformanceController.ReviewInput(
            cycleId,a.employee(),new BigDecimal("4.5"),"Strong work","Improve documentation","On track"));
        assertEquals("SUBMITTED",review.get("status"));

        var course=learning.course(a.user(),new LearningController.CourseInput(
            a.company(),"M75-SAFETY","Safety and compliance","Synthetic training",
            "ONLINE",new BigDecimal("8")));
        UUID courseId=(UUID)course.get("id");
        var enrolled=learning.enroll(a.user(),new LearningController.EnrollmentInput(courseId,a.employee()));
        UUID enrollmentId=(UUID)enrolled.get("id");
        assertEquals("IN_PROGRESS",learning.updateEnrollment(a.user(),enrollmentId,
            new LearningController.CompletionInput("IN_PROGRESS",null)).get("status"));
        assertEquals("COMPLETED",learning.updateEnrollment(a.user(),enrollmentId,
            new LearningController.CompletionInput("COMPLETED",new BigDecimal("95"))).get("status"));
        assertThrows(ResponseStatusException.class,()->learning.updateEnrollment(a.user(),enrollmentId,
            new LearningController.CompletionInput("IN_PROGRESS",null)));
        var skills=learning.skill(a.user(),new LearningController.SkillInput("Cyber hygiene","Security"));
        assertNotNull(skills.get("id"));
        assertEquals(1,learning.courses(a.user()).size());
        assertEquals("COMPLETED",learning.enrollments(a.user()).get(0).get("status"));

        TenantContext.set(b.tenant());
        assertTrue(learning.courses(b.user()).isEmpty(),"Foreign tenant must not see courses");
        assertTrue(performance.cycles(b.user(),b.company()).isEmpty(),"Foreign tenant must not see cycles");
        assertThrows(SecurityException.class,()->tenants.inTenant(a.tenant(),()->learning.courses(a.user())),
            "Tenant context mismatch must not permit tenant switch");
        TenantContext.set(a.tenant());
        assertEquals(1,performance.reviews(a.user(),cycleId).size());
    }

    @Test
    void crossTenantReferencesAreRejectedBeforeCreatingPerformanceOrLearningArtifacts() {
        Fixture a=createFixture(), b=createFixture();
        TenantContext.set(a.tenant());
        assertThrows(ResponseStatusException.class,()->performance.createCycle(a.user(),
            new PerformanceController.CycleInput(b.company(),"Foreign cycle",
                LocalDate.of(2026,1,1),LocalDate.of(2026,12,31))));
        assertThrows(ResponseStatusException.class,()->learning.course(a.user(),
            new LearningController.CourseInput(b.company(),"FOREIGN","Invalid",
                "","ONLINE",BigDecimal.ONE)));
        assertEquals(0,tenants.inTenant(a.tenant(),()->jdbc.queryForObject(
            "SELECT count(*) FROM ld_course WHERE tenant_id=?",Integer.class,a.tenant())));
    }
}
