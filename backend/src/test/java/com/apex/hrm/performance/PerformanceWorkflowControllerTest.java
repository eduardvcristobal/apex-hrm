package com.apex.hrm.performance;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class PerformanceWorkflowControllerTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final TenantJdbcExecutor executor=mock(TenantJdbcExecutor.class);
    private final PerformanceWorkflowController api=new PerformanceWorkflowController(jdbc,executor);
    private HrmUserPrincipal user(String... authorities) {
        return new HrmUserPrincipal(UUID.randomUUID(),UUID.randomUUID(),"sample@example.test","",true,
            Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }
    @Test void managerCannotLinkIdentity() {
        assertThrows(ResponseStatusException.class,()->api.link(
            user("ROLE_MANAGER","ROLE_MFA_VERIFIED"),
            new PerformanceWorkflowController.LinkInput(UUID.randomUUID(),UUID.randomUUID())));
        verifyNoInteractions(jdbc,executor);
    }
    @Test void employeeCannotRequestEntireTeam() {
        assertThrows(ResponseStatusException.class,()->api.teamGoals(user(
            "ROLE_EMPLOYEE","ROLE_MFA_VERIFIED")));
        verifyNoInteractions(jdbc,executor);
    }
    @Test void hrWithoutMfaCannotFinalizeReview() {
        assertThrows(ResponseStatusException.class,()->api.finalizeReview(
            user("ROLE_HR_ADMIN"),UUID.randomUUID()));
        verifyNoInteractions(jdbc,executor);
    }
    @Test void anonymousCannotRecordKpiProgress() {
        assertThrows(ResponseStatusException.class,()->api.progress(null,UUID.randomUUID(),
            new PerformanceWorkflowController.ProgressInput(java.math.BigDecimal.ONE,"test")));
        verifyNoInteractions(jdbc,executor);
    }
    @Test void managerCannotCreateReviewDraftWithoutMfa() {
        assertThrows(ResponseStatusException.class,()->api.draftReview(
            user("ROLE_MANAGER"),new PerformanceWorkflowController.ReviewDraftInput(
                UUID.randomUUID(),UUID.randomUUID())));
        verifyNoInteractions(jdbc,executor);
    }
}
