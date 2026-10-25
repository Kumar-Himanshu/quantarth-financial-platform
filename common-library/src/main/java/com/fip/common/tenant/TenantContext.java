package com.fip.common.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TenantContext {

    private static final Logger log = LoggerFactory.getLogger(TenantContext.class);
    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
        // Prevent instantiation
    }

    public static String getCurrentTenant() {
        return CURRENT_TENANT.get();
    }

    public static void setCurrentTenant(String tenantId) {
        log.debug("Setting current tenant context to: {}", tenantId);
        CURRENT_TENANT.set(tenantId);
    }

    public static void clear() {
        log.debug("Clearing current tenant context");
        CURRENT_TENANT.remove();
    }
}
