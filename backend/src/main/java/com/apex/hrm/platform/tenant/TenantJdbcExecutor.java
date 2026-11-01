package com.apex.hrm.platform.tenant;

import com.apex.hrm.common.TenantContext;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Mandatory gateway for tenant-scoped SQL: SET LOCAL only lasts for this transaction. */
@Component
public class TenantJdbcExecutor {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public TenantJdbcExecutor(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager tx) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(tx);
    }
    public <T> T inTenant(UUID tenantId, Supplier<T> work) {
        if (tenantId == null || TenantContext.NONE.equals(tenantId)) {
            throw new IllegalArgumentException("A real tenant is required");
        }
        // SQL bypasses Hibernate @TenantId: do not override an authenticated workspace.
        UUID authenticatedTenant = TenantContext.get();
        if (authenticatedTenant != null && !authenticatedTenant.equals(tenantId)) {
            throw new SecurityException("Cross-tenant SQL access is forbidden");
        }
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return work.get();
        });
    }
}
