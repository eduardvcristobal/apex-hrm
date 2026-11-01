package com.apex.hrm.payroll.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import java.util.List;
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

/** Negative HTTP authorization checks: a forged X-Tenant-ID cannot elevate non-payroll roles. */
@AutoConfigureMockMvc
@SpringBootTest(properties = {
    "spring.datasource.url=${HRM_TEST_DB_URL:jdbc:postgresql://localhost:5432/apex_hrm_test}",
    "spring.datasource.username=${HRM_TEST_DB_USER:hrm}",
    "spring.datasource.password=${HRM_TEST_DB_PASSWORD:hrm}"
})
@EnabledIfEnvironmentVariable(named = "HRM_TEST_DB_URL", matches = ".+")
class PayrollAuthorizationHttpTest {
    @Autowired MockMvc mvc;

    @Test
    void employeeCannotMarkPayrollPaidEvenWithSpoofedTenantHeader() throws Exception {
        mvc.perform(post("/api/payroll/runs/" + UUID.randomUUID() + "/pay")
                .with(authentication(auth("ROLE_EMPLOYEE", true)))
                .header("X-Tenant-ID", UUID.randomUUID().toString()).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void payrollOfficerWithoutVerifiedMfaCannotMarkPayrollPaid() throws Exception {
        mvc.perform(post("/api/payroll/runs/" + UUID.randomUUID() + "/pay")
                .with(authentication(auth("ROLE_PAYROLL_OFFICER", false))).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void payrollOfficerCannotChangeTimekeepingDtr() throws Exception {
        mvc.perform(post("/api/dtr/process")
                .with(authentication(auth("ROLE_PAYROLL_OFFICER", true))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"2026-10-05\",\"to\":\"2026-10-06\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void hrAdminWithoutVerifiedMfaCannotProcessDtr() throws Exception {
        mvc.perform(post("/api/dtr/process")
                .with(authentication(auth("ROLE_HR_ADMIN", false))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"2026-10-05\",\"to\":\"2026-10-06\"}"))
                .andExpect(status().isForbidden());
    }

    private static UsernamePasswordAuthenticationToken auth(String role, boolean mfa) {
        var authorities = new java.util.ArrayList<SimpleGrantedAuthority>();
        authorities.add(new SimpleGrantedAuthority(role));
        if (mfa) authorities.add(new SimpleGrantedAuthority("ROLE_MFA_VERIFIED"));
        var principal = new HrmUserPrincipal(UUID.randomUUID(), UUID.randomUUID(), "test@example.invalid",
                "unused", true, List.copyOf(authorities));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }
}
