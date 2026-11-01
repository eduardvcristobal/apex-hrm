package com.apex.hrm.common;

import java.util.UUID;

/** Holds the tenant of the current request/thread. Hibernate reads it through TenantIdentifierResolver. */
public final class TenantContext {
    /** Used when no tenant is bound (e.g. global reference data, startup). Never matches a real tenant row. */
    public static final UUID NONE = new UUID(0L, 0L);
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static UUID get() {
        return CURRENT.get();
    }

    public static void set(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
