package com.idp.tenant;

/**
 * Context for storing the current tenant.
 * <p>
 * Propagation details:
 * In reactive stacks (e.g. edge-gateway), use Reactor Context to propagate the tenant.
 * For async boundaries and general observation, use Micrometer's context-propagation
 * framework to automatically move this context into threads.
 * </p>
 */
public final class TenantContext {
    private static final ThreadLocal<TenantId> CONTEXT = new ThreadLocal<>();

    private TenantContext() {
        // Private constructor
    }

    public static void setTenantId(TenantId tenantId) {
        CONTEXT.set(tenantId);
    }

    public static TenantId getTenantId() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
