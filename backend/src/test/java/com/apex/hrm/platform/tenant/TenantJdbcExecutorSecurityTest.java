package com.apex.hrm.platform.tenant;

import com.apex.hrm.common.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TenantJdbcExecutorSecurityTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final TenantJdbcExecutor executor = new TenantJdbcExecutor(jdbc, manager);

    @AfterEach void cleanup() { TenantContext.clear(); }

    @Test void refusesMissingOrSentinelTenantsWithoutDatabaseAccess() {
        assertThrows(IllegalArgumentException.class, () -> executor.inTenant(null, () -> "bad"));
        assertThrows(IllegalArgumentException.class, () -> executor.inTenant(TenantContext.NONE, () -> "bad"));
        verifyNoInteractions(jdbc, manager);
    }

    @Test void refusesCrossTenantSqlBeforeBeginningTransaction() {
        TenantContext.set(UUID.randomUUID());
        UUID foreignTenant = UUID.randomUUID();
        assertThrows(SecurityException.class, () -> executor.inTenant(foreignTenant, () -> "bad"));
        verifyNoInteractions(jdbc, manager);
    }

    @Test void executesWithinCurrentWorkspaceTenantAndSetsLocalDatabaseScope() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(tenant);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.queryForObject(eq("SELECT set_config('app.tenant_id', ?, true)"),
            eq(String.class), eq(tenant.toString()))).thenReturn(tenant.toString());
        assertEquals("ok", executor.inTenant(tenant, () -> "ok"));
        verify(jdbc).queryForObject(eq("SELECT set_config('app.tenant_id', ?, true)"),
            eq(String.class), eq(tenant.toString()));
        verify(manager).commit(any());
    }
}
