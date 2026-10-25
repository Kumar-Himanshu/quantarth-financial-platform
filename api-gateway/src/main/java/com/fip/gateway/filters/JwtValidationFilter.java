package com.fip.gateway.filters;

import com.fip.shared.security.GatewaySecurityConstants;
import com.fip.shared.security.RequestHmacSigner;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class JwtValidationFilter extends AbstractGatewayFilterFactory<JwtValidationFilter.Config> {

    private static final Logger log = LoggerFactory.getLogger(JwtValidationFilter.class);

    private static final List<String> SENSITIVE_HEADERS_TO_STRIP = List.of(
            GatewaySecurityConstants.HEADER_GATEWAY_TOKEN,
            GatewaySecurityConstants.HEADER_GATEWAY_KEY_ID,
            GatewaySecurityConstants.HEADER_GATEWAY_TIMESTAMP,
            GatewaySecurityConstants.HEADER_GATEWAY_NONCE,
            GatewaySecurityConstants.HEADER_GATEWAY_SIGNATURE,
            GatewaySecurityConstants.HEADER_GATEWAY_BODY_DIGEST,
            GatewaySecurityConstants.HEADER_USER_SUBJECT,
            GatewaySecurityConstants.HEADER_USER_ID,
            GatewaySecurityConstants.HEADER_TENANT_ID,
            GatewaySecurityConstants.HEADER_USER_ROLES,
            GatewaySecurityConstants.HEADER_USER_PERMISSIONS
    );

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${gateway.internal.active-key-id:v1}")
    private String activeKeyId;

    @Value("${gateway.internal.secret:${GATEWAY_INTERNAL_SECRET:" + GatewaySecurityConstants.DEFAULT_GATEWAY_TOKEN + "}}")
    private String gatewayInternalSecret;

    private RequestHmacSigner hmacSigner;

    public JwtValidationFilter() {
        super(Config.class);
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

    public static class Config {
        // Dynamic configuration properties if needed
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();

            if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                return onError(exchange, "Missing Authorization Header", HttpStatus.UNAUTHORIZED);
            }

            String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return onError(exchange, "Invalid Authorization Header format. Expected 'Bearer <token>'", HttpStatus.UNAUTHORIZED);
            }

            String token = authHeader.substring(7).trim();

            try {
                SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
                JwtParser parser = Jwts.parser()
                        .verifyWith(key)
                        .requireIssuer(GatewaySecurityConstants.JWT_ISSUER)
                        .requireAudience(GatewaySecurityConstants.JWT_AUDIENCE)
                        .build();
                Claims claims = parser.parseSignedClaims(token).getPayload();

                String subject = claims.getSubject();
                String userId = String.valueOf(claims.getOrDefault("userId", ""));
                String tenantId = String.valueOf(claims.getOrDefault("tenantId", "default"));
                String roles = String.valueOf(claims.getOrDefault("roles", ""));
                String permissions = String.valueOf(claims.getOrDefault("permissions", ""));

                if (subject == null || subject.trim().isEmpty()) {
                    return onError(exchange, "Token subject is invalid or missing", HttpStatus.UNAUTHORIZED);
                }

                if (hmacSigner == null) {
                    initSigner();
                }

                HttpMethod method = request.getMethod();
                boolean hasBody = method != null &&
                        (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) &&
                        (request.getHeaders().getContentLength() > 0 || request.getHeaders().getFirst(HttpHeaders.TRANSFER_ENCODING) != null);

                if (!hasBody) {
                    // Requests without body (GET, DELETE, empty body)
                    long timestamp = System.currentTimeMillis();
                    String nonce = UUID.randomUUID().toString();
                    String bodyDigest = RequestHmacSigner.EMPTY_BODY_DIGEST;
                    String path = request.getURI().getRawPath();
                    String query = request.getURI().getRawQuery() != null ? request.getURI().getRawQuery() : "";

                    String signature = hmacSigner.sign(
                            activeKeyId,
                            method != null ? method.name() : "GET",
                            path,
                            query,
                            subject,
                            userId,
                            tenantId,
                            roles,
                            timestamp,
                            nonce,
                            bodyDigest
                    );

                    ServerHttpRequest mutatedRequest = request.mutate()
                            .headers(headers -> applyForwardedHeaders(headers, timestamp, nonce, signature, bodyDigest,
                                    subject, userId, tenantId, roles, permissions))
                            .build();

                    log.debug("Forwarding authenticated request: method=[{}] path=[{}] tenant=[{}] keyId=[{}]",
                            method, path, tenantId, activeKeyId);
                    return chain.filter(exchange.mutate().request(mutatedRequest).build());
                } else {
                    // Requests with body: buffer, compute digest, sign, and decorate body stream
                    return DataBufferUtils.join(request.getBody())
                            .flatMap(dataBuffer -> {
                                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                                dataBuffer.read(bytes);
                                DataBufferUtils.release(dataBuffer);

                                String bodyDigest = RequestHmacSigner.computeSha256Hex(bytes);
                                long timestamp = System.currentTimeMillis();
                                String nonce = UUID.randomUUID().toString();
                                String path = request.getURI().getRawPath();
                                String query = request.getURI().getRawQuery() != null ? request.getURI().getRawQuery() : "";

                                String signature = hmacSigner.sign(
                                        activeKeyId,
                                        method.name(),
                                        path,
                                        query,
                                        subject,
                                        userId,
                                        tenantId,
                                        roles,
                                        timestamp,
                                        nonce,
                                        bodyDigest
                                );

                                ServerHttpRequest mutatedRequest = request.mutate()
                                        .headers(headers -> applyForwardedHeaders(headers, timestamp, nonce, signature, bodyDigest,
                                                subject, userId, tenantId, roles, permissions))
                                        .build();

                                ServerHttpRequest decoratedRequest = new ServerHttpRequestDecorator(mutatedRequest) {
                                    @Override
                                    public Flux<DataBuffer> getBody() {
                                        return Flux.just(exchange.getResponse().bufferFactory().wrap(bytes));
                                    }
                                };

                                return chain.filter(exchange.mutate().request(decoratedRequest).build());
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                // Empty body fallback
                                long timestamp = System.currentTimeMillis();
                                String nonce = UUID.randomUUID().toString();
                                String bodyDigest = RequestHmacSigner.EMPTY_BODY_DIGEST;
                                String path = request.getURI().getRawPath();
                                String query = request.getURI().getRawQuery() != null ? request.getURI().getRawQuery() : "";

                                String signature = hmacSigner.sign(
                                        activeKeyId,
                                        method.name(),
                                        path,
                                        query,
                                        subject,
                                        userId,
                                        tenantId,
                                        roles,
                                        timestamp,
                                        nonce,
                                        bodyDigest
                                );

                                ServerHttpRequest mutatedRequest = request.mutate()
                                        .headers(headers -> applyForwardedHeaders(headers, timestamp, nonce, signature, bodyDigest,
                                                subject, userId, tenantId, roles, permissions))
                                        .build();

                                return chain.filter(exchange.mutate().request(mutatedRequest).build());
                            }));
                }

            } catch (Exception e) {
                log.warn("JWT validation failed: {}", e.getMessage());
                return onError(exchange, "Token Validation Failed: " + e.getMessage(), HttpStatus.UNAUTHORIZED);
            }
        };
    }

    private void applyForwardedHeaders(HttpHeaders headers, long timestamp, String nonce, String signature,
                                       String bodyDigest, String subject, String userId, String tenantId,
                                       String roles, String permissions) {
        // 1. SECURITY: Strip all client-supplied sensitive identity & gateway headers
        for (String header : SENSITIVE_HEADERS_TO_STRIP) {
            headers.remove(header);
        }

        // 2. Inject request-bound HMAC authentication headers
        headers.set(GatewaySecurityConstants.HEADER_GATEWAY_KEY_ID, activeKeyId);
        headers.set(GatewaySecurityConstants.HEADER_GATEWAY_TIMESTAMP, String.valueOf(timestamp));
        headers.set(GatewaySecurityConstants.HEADER_GATEWAY_NONCE, nonce);
        headers.set(GatewaySecurityConstants.HEADER_GATEWAY_SIGNATURE, signature);
        headers.set(GatewaySecurityConstants.HEADER_GATEWAY_BODY_DIGEST, bodyDigest);
        headers.set(GatewaySecurityConstants.HEADER_GATEWAY_TOKEN, gatewayInternalSecret); // Backward compatibility

        // 3. Inject validated identity context headers
        headers.set(GatewaySecurityConstants.HEADER_USER_SUBJECT, subject);
        headers.set(GatewaySecurityConstants.HEADER_USER_ID, userId);
        headers.set(GatewaySecurityConstants.HEADER_TENANT_ID, tenantId);
        headers.set(GatewaySecurityConstants.HEADER_USER_ROLES, roles);
        headers.set(GatewaySecurityConstants.HEADER_USER_PERMISSIONS, permissions);
    }

    private Mono<Void> onError(ServerWebExchange exchange, String detail, HttpStatus status) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        String problemJson = String.format(
                "{\"type\":\"https://fip.com/errors/%s\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"timestamp\":\"%s\"}",
                status.name().toLowerCase(),
                status.getReasonPhrase(),
                status.value(),
                detail.replace("\"", "\\\""),
                Instant.now().toString()
        );

        DataBuffer buffer = response.bufferFactory().wrap(problemJson.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
