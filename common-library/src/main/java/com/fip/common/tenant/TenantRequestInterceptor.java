package com.fip.common.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

@Component
public class TenantRequestInterceptor implements HandlerInterceptor {

    public static final String CORRELATION_HEADER = "X-Correlation-ID";
    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String USER_SUBJECT_HEADER = "X-User-Subject";
    public static final String USER_ROLES_HEADER = "X-User-Roles";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Resolve & Set Trusted Tenant
        String tenantId = TenantResolver.resolveTenant(request);
        TenantContext.setCurrentTenant(tenantId);
        MDC.put("tenantId", tenantId);

        // Correlation ID Tracing
        String correlationId = request.getHeader(CORRELATION_HEADER);
        if (correlationId == null || correlationId.trim().isEmpty()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put("correlationId", correlationId);
        response.setHeader(CORRELATION_HEADER, correlationId);

        // Identity context in MDC for structured audit logs
        String userSubject = request.getHeader(USER_SUBJECT_HEADER);
        if (userSubject != null && !userSubject.isEmpty()) {
            MDC.put("userSubject", userSubject);
        }

        String userRoles = request.getHeader(USER_ROLES_HEADER);
        if (userRoles != null && !userRoles.isEmpty()) {
            MDC.put("userRoles", userRoles);
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantContext.clear();
        MDC.clear();
    }
}
