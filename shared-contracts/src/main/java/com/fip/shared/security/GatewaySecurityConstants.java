package com.fip.shared.security;

public final class GatewaySecurityConstants {

    private GatewaySecurityConstants() {
        // Prevent instantiation
    }

    public static final String HEADER_GATEWAY_TOKEN = "X-Gateway-Token";
    public static final String HEADER_GATEWAY_KEY_ID = "X-Gateway-Key-Id";
    public static final String HEADER_GATEWAY_TIMESTAMP = "X-Gateway-Timestamp";
    public static final String HEADER_GATEWAY_NONCE = "X-Gateway-Nonce";
    public static final String HEADER_GATEWAY_SIGNATURE = "X-Gateway-Signature";
    public static final String HEADER_GATEWAY_BODY_DIGEST = "X-Gateway-Body-Digest";

    public static final String HEADER_USER_SUBJECT = "X-User-Subject";
    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";
    public static final String HEADER_USER_ROLES = "X-User-Roles";
    public static final String HEADER_USER_PERMISSIONS = "X-User-Permissions";

    public static final String JWT_ISSUER = "quantarth-fip";
    public static final String JWT_AUDIENCE = "fip-platform";

    public static final String DEFAULT_GATEWAY_KEY_ID = "v1";
    public static final String DEFAULT_GATEWAY_TOKEN = "quantarth-fip-internal-mesh-secret-2026";
}

