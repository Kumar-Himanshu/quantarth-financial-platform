package com.fip.ingestion.adapters.out.eventengine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.common.response.StandardResponse;
import com.fip.common.tenant.TenantContext;
import com.fip.shared.events.CanonicalEvent;
import com.fip.shared.security.GatewaySecurityConstants;
import com.fip.shared.security.RequestHmacSigner;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class EventEngineClient {

    private static final Logger log = LoggerFactory.getLogger(EventEngineClient.class);

    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Value("${event-engine.url:http://localhost:8083}")
    private String eventEngineBaseUrl;

    @Value("${gateway.internal.active-key-id:v1}")
    private String activeKeyId;

    @Value("${gateway.internal.secret:${GATEWAY_INTERNAL_SECRET:" + GatewaySecurityConstants.DEFAULT_GATEWAY_TOKEN + "}}")
    private String gatewayInternalSecret;

    private RequestHmacSigner hmacSigner;

    public EventEngineClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder().build();
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

    public record BatchForwardResult(
            List<CanonicalEvent> persistedEvents,
            List<FailedEvent> failures
    ) {}

    public record FailedEvent(CanonicalEvent event, String errorCode, String errorMessage) {}

    public BatchForwardResult forwardBatch(List<CanonicalEvent> events) {
        if (events == null || events.isEmpty()) {
            return new BatchForwardResult(Collections.emptyList(), Collections.emptyList());
        }

        if (hmacSigner == null) {
            initSigner();
        }

        String path = "/api/v1/events/canonical/batch";
        String targetUrl = eventEngineBaseUrl + path;
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = events.get(0).tenantId();
        }

        try {
            byte[] bodyBytes = objectMapper.writeValueAsBytes(events);
            String bodyDigest = RequestHmacSigner.computeSha256Hex(bodyBytes);
            long timestamp = System.currentTimeMillis();
            String nonce = UUID.randomUUID().toString();
            String subject = "service:ingestion-service";
            String userId = "system:ingestion-service";
            String roles = "ROLE_ADMIN,ROLE_ANALYST";

            String signature = hmacSigner.sign(
                    activeKeyId,
                    "POST",
                    path,
                    "",
                    subject,
                    userId,
                    tenantId,
                    roles,
                    timestamp,
                    nonce,
                    bodyDigest
            );

            String responseBody = restClient.post()
                    .uri(targetUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(GatewaySecurityConstants.HEADER_GATEWAY_KEY_ID, activeKeyId)
                    .header(GatewaySecurityConstants.HEADER_GATEWAY_TIMESTAMP, String.valueOf(timestamp))
                    .header(GatewaySecurityConstants.HEADER_GATEWAY_NONCE, nonce)
                    .header(GatewaySecurityConstants.HEADER_GATEWAY_SIGNATURE, signature)
                    .header(GatewaySecurityConstants.HEADER_GATEWAY_BODY_DIGEST, bodyDigest)
                    .header(GatewaySecurityConstants.HEADER_TENANT_ID, tenantId)
                    .header(GatewaySecurityConstants.HEADER_USER_SUBJECT, subject)
                    .header(GatewaySecurityConstants.HEADER_USER_ID, userId)
                    .header(GatewaySecurityConstants.HEADER_USER_ROLES, roles)
                    .header(GatewaySecurityConstants.HEADER_USER_PERMISSIONS, "fip:events:publish")
                    .body(bodyBytes)
                    .retrieve()
                    .body(String.class);

            StandardResponse<List<CanonicalEvent>> parsed = objectMapper.readValue(
                    responseBody, new TypeReference<>() {});

            List<CanonicalEvent> persisted = parsed.getData() != null ? parsed.getData() : events;
            log.info("Successfully persisted batch of [{}] canonical events to event-engine", persisted.size());
            return new BatchForwardResult(persisted, Collections.emptyList());

        } catch (Exception ex) {
            log.warn("Batch dispatch of [{}] events failed ({}); initiating row-by-row fallback isolation",
                    events.size(), ex.getMessage());
            return fallbackRowByRow(events, tenantId);
        }
    }

    private BatchForwardResult fallbackRowByRow(List<CanonicalEvent> events, String tenantId) {
        List<CanonicalEvent> successful = new ArrayList<>();
        List<FailedEvent> failures = new ArrayList<>();
        String singlePath = "/api/v1/events/canonical";
        String targetUrl = eventEngineBaseUrl + singlePath;

        for (CanonicalEvent event : events) {
            try {
                byte[] bodyBytes = objectMapper.writeValueAsBytes(event);
                String bodyDigest = RequestHmacSigner.computeSha256Hex(bodyBytes);
                long timestamp = System.currentTimeMillis();
                String nonce = UUID.randomUUID().toString();
                String subject = "service:ingestion-service";
                String userId = "system:ingestion-service";
                String roles = "ROLE_ADMIN,ROLE_ANALYST";

                String signature = hmacSigner.sign(
                        activeKeyId, "POST", singlePath, "", subject, userId, tenantId, roles,
                        timestamp, nonce, bodyDigest);

                String responseBody = restClient.post()
                        .uri(targetUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_KEY_ID, activeKeyId)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_TIMESTAMP, String.valueOf(timestamp))
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_NONCE, nonce)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_SIGNATURE, signature)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_BODY_DIGEST, bodyDigest)
                        .header(GatewaySecurityConstants.HEADER_TENANT_ID, tenantId)
                        .header(GatewaySecurityConstants.HEADER_USER_SUBJECT, subject)
                        .header(GatewaySecurityConstants.HEADER_USER_ID, userId)
                        .header(GatewaySecurityConstants.HEADER_USER_ROLES, roles)
                        .header(GatewaySecurityConstants.HEADER_USER_PERMISSIONS, "fip:events:publish")
                        .body(bodyBytes)
                        .retrieve()
                        .body(String.class);

                StandardResponse<CanonicalEvent> parsed = objectMapper.readValue(
                        responseBody, new TypeReference<>() {});
                successful.add(parsed.getData() != null ? parsed.getData() : event);

            } catch (Exception e) {
                log.info("Isolated row failure for entity [{}] in event-engine: {}", event.entityId(), e.getMessage());
                failures.add(new FailedEvent(event, "EVENT_ENGINE_REJECTED", e.getMessage()));
            }
        }

        return new BatchForwardResult(successful, failures);
    }

    public boolean checkPaymentExists(String tenantId, String paymentId) {
        if (paymentId == null || paymentId.isBlank() || "EXT-PAY-UNKNOWN".equalsIgnoreCase(paymentId)) {
            return false;
        }

        if (hmacSigner == null) {
            initSigner();
        }

        String path = "/api/v1/events";
        String query = "entityType=PAYMENT&entityId=" + paymentId;
        String targetUrl = eventEngineBaseUrl + path + "?" + query;

        try {
            long timestamp = System.currentTimeMillis();
            String nonce = UUID.randomUUID().toString();
            String subject = "service:ingestion-service";
            String userId = "system:ingestion-service";
            String roles = "ROLE_ADMIN,ROLE_ANALYST";
            byte[] emptyBody = new byte[0];
            String bodyDigest = RequestHmacSigner.computeSha256Hex(emptyBody);

            String signature = hmacSigner.sign(
                    activeKeyId, "GET", path, query, subject, userId, tenantId, roles,
                    timestamp, nonce, bodyDigest);

            String responseBody = restClient.get()
                    .uri(targetUrl)
                    .header(GatewaySecurityConstants.HEADER_GATEWAY_KEY_ID, activeKeyId)
                    .header(GatewaySecurityConstants.HEADER_GATEWAY_TIMESTAMP, String.valueOf(timestamp))
                    .header(GatewaySecurityConstants.HEADER_GATEWAY_NONCE, nonce)
                    .header(GatewaySecurityConstants.HEADER_GATEWAY_SIGNATURE, signature)
                    .header(GatewaySecurityConstants.HEADER_GATEWAY_BODY_DIGEST, bodyDigest)
                    .header(GatewaySecurityConstants.HEADER_TENANT_ID, tenantId)
                    .header(GatewaySecurityConstants.HEADER_USER_SUBJECT, subject)
                    .header(GatewaySecurityConstants.HEADER_USER_ID, userId)
                    .header(GatewaySecurityConstants.HEADER_USER_ROLES, roles)
                    .retrieve()
                    .body(String.class);

            if (responseBody == null || responseBody.isBlank()) {
                return false;
            }

            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(responseBody);
            com.fasterxml.jackson.databind.JsonNode data = root.get("data");
            if (data != null) {
                com.fasterxml.jackson.databind.JsonNode content = data.get("content");
                if (content != null && content.isArray() && !content.isEmpty()) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            log.debug("Payment check for paymentId [{}] returned exception: {}", paymentId, ex.getMessage());
            return false;
        }
    }
}
