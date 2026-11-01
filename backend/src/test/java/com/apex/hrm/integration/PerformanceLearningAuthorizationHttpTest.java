package com.apex.hrm.integration;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Spring Security route matcher checks for M5/M6; no mock repositories. */
@AutoConfigureMockMvc
@SpringBootTest(properties={
    "spring.datasource.url=${HRM_TEST_DB_URL:jdbc:postgresql://localhost:5432/apex_hrm_m75_cert}",
    "spring.datasource.username=${HRM_TEST_DB_USER:hrm}",
    "spring.datasource.password=${HRM_TEST_DB_PASSWORD:}"
})
@EnabledIfEnvironmentVariable(named="HRM_TEST_DB_URL",matches=".+")
class PerformanceLearningAuthorizationHttpTest {
    @Autowired MockMvc mvc;

    private static UsernamePasswordAuthenticationToken auth(UUID tenant, String... roles) {
        var authorities=Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        var principal=new HrmUserPrincipal(UUID.randomUUID(),tenant,"sample@example.invalid",
            "",true,authorities);
        return new UsernamePasswordAuthenticationToken(principal,null,authorities);
    }

    @Test void unauthenticatedPerformanceMutationIsDenied() throws Exception {
        mvc.perform(post("/api/v1/performance/cycles").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
    }

    @Test void employeeWithMfaCannotCreateCompanyPerformanceCycle() throws Exception {
        mvc.perform(post("/api/v1/performance/cycles")
            .with(authentication(auth(UUID.randomUUID(),"ROLE_EMPLOYEE","ROLE_MFA_VERIFIED")))
            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test void managerWithoutMfaCannotViewTeamReviews() throws Exception {
        mvc.perform(get("/api/v1/performance/team/reviews")
            .with(authentication(auth(UUID.randomUUID(),"ROLE_MANAGER"))))
            .andExpect(status().isForbidden());
    }

    @Test void employeeCannotCreateLearningCourseEvenWithMfa() throws Exception {
        mvc.perform(post("/api/v1/learning/courses")
            .with(authentication(auth(UUID.randomUUID(),"ROLE_EMPLOYEE","ROLE_MFA_VERIFIED")))
            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test void hrAdministratorMustVerifyMfaBeforeMutatingLearning() throws Exception {
        mvc.perform(post("/api/v1/learning/enrollments")
            .with(authentication(auth(UUID.randomUUID(),"ROLE_HR_ADMIN")))
            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test void forgedTenantHeaderDoesNotElevateStudentPermissions() throws Exception {
        mvc.perform(post("/api/v1/learning/courses")
            .with(authentication(auth(UUID.randomUUID(),"ROLE_EMPLOYEE","ROLE_MFA_VERIFIED")))
            .header("X-Tenant-ID",UUID.randomUUID().toString())
            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test void employeeCannotReadTenantWideLearningCatalog() throws Exception {
        mvc.perform(get("/api/v1/learning/courses")
            .with(authentication(auth(UUID.randomUUID(),"ROLE_EMPLOYEE","ROLE_MFA_VERIFIED"))))
            .andExpect(status().isForbidden());
    }
}
