package com.fip.common.tenant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

public final class TenantResolver {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String DEFAULT_TENANT = "default";

    private TenantResolver() {
        // Prevent instantiation
    }

    /**
     * Resolves tenant strictly from the trusted, validated X-Tenant-Id header.
     * Arbitrary client-supplied query parameters are explicitly rejected to prevent tenant spoofing.
     */
    public static String resolveTenant(HttpServletRequest request) {
        String tenantId = request.getHeader(TENANT_HEADER);
        if (StringUtils.hasText(tenantId)) {
            return tenantId.trim();
        }
        return DEFAULT_TENANT;
    }
}
