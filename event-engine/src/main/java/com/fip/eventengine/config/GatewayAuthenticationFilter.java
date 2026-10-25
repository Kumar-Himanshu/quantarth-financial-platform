package com.fip.eventengine.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.common.tenant.TenantContext;
import com.fip.shared.security.GatewaySecurityConstants;
import com.fip.shared.security.RequestHmacSigner;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates request-bound HMAC-SHA256 service authentication from API Gateway.
 * Ensures request integrity, caller identity context binding, timestamp validity,
 * and sliding-window replay protection.
 */
@Component
public class GatewayAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GatewayAuthenticationFilter.class);

    private final ObjectMapper objectMapper;

    @Value("${gateway.internal.active-key-id:v1}")
    private String activeKeyId;

    @Value("${gateway.internal.secret:${GATEWAY_INTERNAL_SECRET:" + GatewaySecurityConstants.DEFAULT_GATEWAY_TOKEN + "}}")
    private String gatewayInternalSecret;

    private RequestHmacSigner hmacSigner;

    public GatewayAuthenticationFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initSigner() {
        if (hmacSigner == null) {
            hmacSigner = new RequestHmacSigner(activeKeyId, Map.of(activeKeyId, gatewayInternalSecret));
        }
    }

    public void setHmacSigner(RequestHmacSigner hmacSigner) {
        this.hmacSigner = hmacSigner;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // Allow public observability & documentation endpoints
        if (path.startsWith("/actuator") || path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Cache body for HMAC verification and subsequent controller consumption
        CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request);

        String keyId = wrappedRequest.getHeader(GatewaySecurityConstants.HEADER_GATEWAY_KEY_ID);
        String timestampHeader = wrappedRequest.getHeader(GatewaySecurityConstants.HEADER_GATEWAY_TIMESTAMP);
        String nonce = wrappedRequest.getHeader(GatewaySecurityConstants.HEADER_GATEWAY_NONCE);
        String signature = wrappedRequest.getHeader(GatewaySecurityConstants.HEADER_GATEWAY_SIGNATURE);
        String bodyDigestHeader = wrappedRequest.getHeader(GatewaySecurityConstants.HEADER_GATEWAY_BODY_DIGEST);

        // Reject immediately if any required HMAC authentication header is missing
        if (keyId == null || timestampHeader == null || nonce == null || signature == null) {
            log.warn("GATEWAY BYPASS ATTEMPT DETECTED: Missing required HMAC headers from [{}] for URI [{}]",
                    request.getRemoteAddr(), path);
            rejectRequest(response, "Access denied: Missing internal service authentication headers. Request must pass through API Gateway.");
            return;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader);
        } catch (NumberFormatException e) {
            rejectRequest(response, "Access denied: Invalid authentication timestamp format.");
            return;
        }

        // Compute actual body digest from request bytes
        byte[] bodyBytes = wrappedRequest.getCachedBody();
        String actualBodyDigest = RequestHmacSigner.computeSha256Hex(bodyBytes);

        // Verify body digest header matches computed body digest (if header provided)
        if (bodyDigestHeader != null && !bodyDigestHeader.equalsIgnoreCase(actualBodyDigest)) {
            log.warn("Body digest tampering detected from [{}] for URI [{}]", request.getRemoteAddr(), path);
            rejectRequest(response, "Access denied: Request body digest tampering detected.");
            return;
        }

        if (hmacSigner == null) {
            initSigner();
        }

        String method = wrappedRequest.getMethod();
        String query = wrappedRequest.getQueryString() != null ? wrappedRequest.getQueryString() : "";
        String subject = wrappedRequest.getHeader(GatewaySecurityConstants.HEADER_USER_SUBJECT);
        String userId = wrappedRequest.getHeader(GatewaySecurityConstants.HEADER_USER_ID);
        String tenantId = wrappedRequest.getHeader(GatewaySecurityConstants.HEADER_TENANT_ID);
        String rolesHeader = wrappedRequest.getHeader(GatewaySecurityConstants.HEADER_USER_ROLES);

        boolean signatureValid = hmacSigner.verify(
                keyId,
                timestamp,
                nonce,
                signature,
                method,
                path,
                query,
                subject != null ? subject : "",
                userId != null ? userId : "",
                tenantId != null ? tenantId : "",
                rolesHeader != null ? rolesHeader : "",
                actualBodyDigest
        );

        if (!signatureValid) {
            log.warn("GATEWAY AUTHENTICATION FAILED: Invalid, expired, or replayed signature from [{}] for URI [{}] keyId [{}]",
                    request.getRemoteAddr(), path, keyId);
            rejectRequest(response, "Access denied: Request signature verification failed, expired, or replayed.");
            return;
        }

        // Set verified tenant context
        if (tenantId != null && !tenantId.trim().isEmpty()) {
            TenantContext.setCurrentTenant(tenantId.trim());
        }

        // Set verified security context
        if (subject != null && !subject.trim().isEmpty()) {
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();

            if (rolesHeader != null && !rolesHeader.trim().isEmpty()) {
                String cleanRoles = rolesHeader.replaceAll("[\\[\\]\\s]", "");
                for (String role : cleanRoles.split(",")) {
                    if (!role.isEmpty()) {
                        String formattedRole = role.startsWith("ROLE_") ? role : "ROLE_" + role;
                        authorities.add(new SimpleGrantedAuthority(formattedRole));
                    }
                }
            }

            String permissionsHeader = wrappedRequest.getHeader(GatewaySecurityConstants.HEADER_USER_PERMISSIONS);
            if (permissionsHeader != null && !permissionsHeader.trim().isEmpty()) {
                String cleanPerms = permissionsHeader.replaceAll("[\\[\\]\\s]", "");
                for (String perm : cleanPerms.split(",")) {
                    if (!perm.isEmpty()) {
                        authorities.add(new SimpleGrantedAuthority(perm));
                    }
                }
            }

            PreAuthenticatedAuthenticationToken authentication =
                    new PreAuthenticatedAuthenticationToken(subject, "N/A", authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        try {
            filterChain.doFilter(wrappedRequest, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void rejectRequest(HttpServletResponse response, String detail) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail);
        problem.setTitle("Unauthorized");
        problem.setType(URI.create("https://fip.com/errors/unauthorized"));
        problem.setProperty("timestamp", Instant.now());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
