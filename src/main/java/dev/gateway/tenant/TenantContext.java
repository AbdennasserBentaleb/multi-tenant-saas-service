package dev.gateway.tenant;

import org.springframework.lang.NonNull;
import java.util.UUID;

/**
 * Sterile ThreadLocal Context for the tenant.
 * Guarantees that ThreadLocal is thoroughly cleaned to prevent pool poisoning.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    @NonNull
    public static UUID getCurrentTenant() {
        UUID tenantId = CURRENT_TENANT.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "No tenant bound in the current scope. Ensure TenantFilter is registered.");
        }
        return tenantId;
    }

    public static void setTenantId(@NonNull UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId cannot be null. Use clear() to unset.");
        }
        CURRENT_TENANT.set(tenantId);
    }

    /**
     * Completely removes the thread-local variable to prevent Tomcat thread-pool poisoning.
     */
    public static void clear() {
        CURRENT_TENANT.remove();
    }

    public static boolean isBound() {
        return CURRENT_TENANT.get() != null;
    }
}
