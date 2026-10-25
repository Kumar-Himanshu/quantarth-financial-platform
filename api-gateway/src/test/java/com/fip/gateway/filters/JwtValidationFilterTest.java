package com.fip.gateway.filters;

import com.fip.shared.security.GatewaySecurityConstants;
import com.fip.shared.security.RequestHmacSigner;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JwtValidationFilterTest {

    private JwtValidationFilter filter;
    private final String secret = "1234567890123456789012345678901234567890123456789012345678901234";
    private final String gatewaySecret = "quantarth-fip-internal-mesh-secret-2026";

    @BeforeEach
    void setUp() {
        filter = new JwtValidationFilter();
        ReflectionTestUtils.setField(filter, "jwtSecret", secret);
        ReflectionTestUtils.setField(filter, "gatewayInternalSecret", gatewaySecret);
        ReflectionTestUtils.setField(filter, "activeKeyId", "v1");
        filter.setHmacSigner(new RequestHmacSigner("v1", Map.of("v1", gatewaySecret)));
    }

    @Test
    @DisplayName("apply should return 401 when Authorization header is missing")
    void apply_missingAuthorizationHeader_shouldReturn401() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/events").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        GatewayFilter gatewayFilter = filter.apply(new JwtValidationFilter.Config());

        StepVerifier.create(gatewayFilter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("apply should return 401 when token is expired")
    void apply_expiredToken_shouldReturn401() {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        String expiredToken = Jwts.builder()
                .issuer("quantarth-fip")
                .audience().add("fip-platform").and()
                .subject("priya")
                .claim("tenantId", "tenant-alpha")
                .issuedAt(new Date(System.currentTimeMillis() - 100000))
                .expiration(new Date(System.currentTimeMillis() - 1000))
                .signWith(key)
                .compact();

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/events")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        GatewayFilter gatewayFilter = filter.apply(new JwtValidationFilter.Config());

        StepVerifier.create(gatewayFilter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("apply should return 401 when token has invalid issuer or audience")
    void apply_invalidIssuerOrAudience_shouldReturn401() {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        String untrustedToken = Jwts.builder()
                .issuer("untrusted-issuer")
                .audience().add("wrong-audience").and()
                .subject("priya")
                .claim("tenantId", "tenant-alpha")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(key)
                .compact();

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/events")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + untrustedToken)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        GatewayFilter gatewayFilter = filter.apply(new JwtValidationFilter.Config());

        StepVerifier.create(gatewayFilter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("apply should strip client-spoofed headers and inject verified JWT claims and request-bound HMAC signature")
    void apply_spoofedHeaders_shouldStripAndInjectVerifiedClaimsAndHmac() {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        String validToken = Jwts.builder()
                .issuer("quantarth-fip")
                .audience().add("fip-platform").and()
                .subject("priya_analyst")
                .claim("userId", "11111111-1111-1111-1111-111111111111")
                .claim("tenantId", "tenant-alpha")
                .claim("roles", List.of("ROLE_ANALYST"))
                .claim("permissions", List.of("fip:events:publish", "fip:read"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(key)
                .compact();

        // Client maliciously supplies spoofed headers trying to claim ROLE_SUPER_ADMIN, fake signature, and tenant-beta
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/events")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken)
                .header(GatewaySecurityConstants.HEADER_GATEWAY_TOKEN, "forged-client-token")
                .header(GatewaySecurityConstants.HEADER_GATEWAY_SIGNATURE, "forged-client-signature")
                .header(GatewaySecurityConstants.HEADER_TENANT_ID, "malicious-tenant-beta")
                .header(GatewaySecurityConstants.HEADER_USER_ROLES, "ROLE_SUPER_ADMIN")
                .header(GatewaySecurityConstants.HEADER_USER_SUBJECT, "root")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        when(chain.filter(captor.capture())).thenReturn(Mono.empty());

        GatewayFilter gatewayFilter = filter.apply(new JwtValidationFilter.Config());

        StepVerifier.create(gatewayFilter.filter(exchange, chain))
                .verifyComplete();

        ServerWebExchange capturedExchange = captor.getValue();
        HttpHeaders forwardedHeaders = capturedExchange.getRequest().getHeaders();

        // Verified that spoofed headers were replaced by genuine JWT claims
        assertThat(forwardedHeaders.getFirst(GatewaySecurityConstants.HEADER_TENANT_ID)).isEqualTo("tenant-alpha");
        assertThat(forwardedHeaders.getFirst(GatewaySecurityConstants.HEADER_USER_SUBJECT)).isEqualTo("priya_analyst");
        assertThat(forwardedHeaders.getFirst(GatewaySecurityConstants.HEADER_USER_ROLES)).contains("ROLE_ANALYST");
        assertThat(forwardedHeaders.getFirst(GatewaySecurityConstants.HEADER_USER_ROLES)).doesNotContain("ROLE_SUPER_ADMIN");

        // Verified HMAC-SHA256 authentication headers were injected
        assertThat(forwardedHeaders.getFirst(GatewaySecurityConstants.HEADER_GATEWAY_KEY_ID)).isEqualTo("v1");
        assertThat(forwardedHeaders.getFirst(GatewaySecurityConstants.HEADER_GATEWAY_TIMESTAMP)).isNotNull();
        assertThat(forwardedHeaders.getFirst(GatewaySecurityConstants.HEADER_GATEWAY_NONCE)).isNotNull();
        assertThat(forwardedHeaders.getFirst(GatewaySecurityConstants.HEADER_GATEWAY_SIGNATURE)).isNotEqualTo("forged-client-signature");
        assertThat(forwardedHeaders.getFirst(GatewaySecurityConstants.HEADER_GATEWAY_BODY_DIGEST)).isEqualTo(RequestHmacSigner.EMPTY_BODY_DIGEST);

        // Verify the injected HMAC signature is cryptographically valid against the request attributes
        RequestHmacSigner verifier = new RequestHmacSigner("v1", Map.of("v1", gatewaySecret));
        boolean isValidSig = verifier.verify(
                forwardedHeaders.getFirst(GatewaySecurityConstants.HEADER_GATEWAY_KEY_ID),
                Long.parseLong(forwardedHeaders.getFirst(GatewaySecurityConstants.HEADER_GATEWAY_TIMESTAMP)),
                forwardedHeaders.getFirst(GatewaySecurityConstants.HEADER_GATEWAY_NONCE),
                forwardedHeaders.getFirst(GatewaySecurityConstants.HEADER_GATEWAY_SIGNATURE),
                "GET",
                "/api/v1/events",
                "",
                "priya_analyst",
                "11111111-1111-1111-1111-111111111111",
                "tenant-alpha",
                "[ROLE_ANALYST]",
                RequestHmacSigner.EMPTY_BODY_DIGEST
        );
        assertThat(isValidSig).isTrue();
    }
}
