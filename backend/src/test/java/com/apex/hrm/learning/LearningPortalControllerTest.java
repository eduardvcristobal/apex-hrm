package com.apex.hrm.learning;

import com.apex.hrm.platform.security.HrmUserPrincipal;
import com.apex.hrm.platform.tenant.TenantJdbcExecutor;
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

class LearningPortalControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TenantJdbcExecutor tenants = mock(TenantJdbcExecutor.class);
    private final HrmUserPrincipal user = new HrmUserPrincipal(UUID.randomUUID(),UUID.randomUUID(),
        "employee@example.test","",true,List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
    private LearningPortalController controller;

    @BeforeEach
    void setup() {
        reset(jdbc,tenants);
        when(tenants.inTenant(eq(user.tenantId()),any())).thenAnswer(invocation ->
            ((Supplier<?>)invocation.getArgument(1)).get());
        controller=new LearningPortalController(jdbc,tenants);
    }

    @Test
    void unlinkedUserCannotSeeAnotherEmployeesRecords() {
        when(jdbc.query(contains("FROM ld_employee_account"),any(org.springframework.jdbc.core.RowMapper.class),
                eq(user.tenantId()),eq(user.id()))).thenReturn(List.of());
        assertThrows(ResponseStatusException.class,()->controller.mine(user));
        verify(jdbc,never()).queryForList(contains("FROM ld_enrollment"),any(Object[].class));
    }

    @Test
    void linkedProfileMustBeOwnedByCurrentAuthenticatedIdentity() {
        when(jdbc.query(contains("FROM ld_employee_account"),any(org.springframework.jdbc.core.RowMapper.class),
                eq(user.tenantId()),eq(user.id()))).thenReturn(List.of(UUID.randomUUID()));
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of());
        var result=controller.mine(user);
        assertEquals(3,result.size());
        verify(jdbc).query(contains("FROM ld_employee_account"),
            any(org.springframework.jdbc.core.RowMapper.class),eq(user.tenantId()),eq(user.id()));
        verify(jdbc,atLeastOnce()).queryForList(anyString(),eq(user.tenantId()),any(UUID.class));
    }

    @Test
    void foreignTenantAccountCannotBeLinked() {
        when(jdbc.queryForObject(contains("FROM auth_identity"),eq(Boolean.class),any(Object[].class)))
            .thenReturn(false);
        assertThrows(ResponseStatusException.class,()->controller.link(user,
            new LearningPortalController.AccountLink(UUID.randomUUID(),UUID.randomUUID())));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
