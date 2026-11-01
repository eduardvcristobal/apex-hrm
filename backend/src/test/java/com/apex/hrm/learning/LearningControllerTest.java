package com.apex.hrm.learning;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LearningControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TenantJdbcExecutor tenants = mock(TenantJdbcExecutor.class);
    private final HrmUserPrincipal user = new HrmUserPrincipal(UUID.randomUUID(),UUID.randomUUID(),
        "hr@example.test","",true,List.of(new SimpleGrantedAuthority("ROLE_HR_ADMIN")));
    private LearningController controller;

    @BeforeEach
    void setup() {
        reset(jdbc,tenants);
        when(tenants.inTenant(eq(user.tenantId()),any())).thenAnswer(invocation ->
            ((Supplier<?>) invocation.getArgument(1)).get());
        controller = new LearningController(jdbc,tenants);
    }

    @Test
    void invalidEnrollmentTransitionDoesNotWrite() {
        assertThrows(ResponseStatusException.class,() ->
            controller.updateEnrollment(user,UUID.randomUUID(),
                new LearningController.CompletionInput("APPROVED",new BigDecimal("80"))));
        verifyNoInteractions(jdbc);
    }

    @Test
    void courseMustUseSupportedDeliveryMode() {
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(Object[].class))).thenReturn(true);
        assertThrows(ResponseStatusException.class,() ->
            controller.course(user,new LearningController.CourseInput(
                UUID.randomUUID(),"SAFETY","Safety course","", "EXTERNAL",BigDecimal.ONE)));
        verify(jdbc,never()).queryForObject(startsWith("INSERT"),eq(UUID.class),any(Object[].class));
    }

    @Test
    void certificateExpiryCannotPrecedeIssueDate() {
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(Object[].class))).thenReturn(true);
        assertThrows(ResponseStatusException.class,() ->
            controller.certificate(user,new LearningController.CertificationInput(
                UUID.randomUUID(),null,"License","Issuer","REF",LocalDate.of(2026,10,1),
                LocalDate.of(2026,9,1))));
        verify(jdbc,never()).queryForObject(startsWith("INSERT"),eq(UUID.class),any(Object[].class));
    }

    @Test
    void employeeOutsideTenantCannotBeCertified() {
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(Object[].class))).thenReturn(false);
        assertThrows(ResponseStatusException.class,() ->
            controller.certificate(user,new LearningController.CertificationInput(
                UUID.randomUUID(),null,"License","Issuer",null,LocalDate.now(),null)));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
