package com.fip.eventengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.eventengine.adapters.in.messaging.RabbitMQEventListener;
import com.fip.eventengine.adapters.out.persistence.EventOutboxJpaEntity;
import com.fip.eventengine.adapters.out.persistence.EventOutboxJpaRepository;
import com.fip.eventengine.adapters.out.persistence.FinancialEventJpaEntity;
import com.fip.eventengine.adapters.out.persistence.FinancialEventJpaRepository;
import com.fip.eventengine.service.OutboxPollingPublisher;
import com.fip.shared.events.CanonicalEvent;
import com.fip.shared.security.GatewaySecurityConstants;
import com.fip.shared.security.RequestHmacSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class VerticalSliceIntegrationTest {

    private static final String GATEWAY_SECRET = GatewaySecurityConstants.DEFAULT_GATEWAY_TOKEN;
    private static final String ACTIVE_KEY_ID = "v1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FinancialEventJpaRepository repository;

    @Autowired
    private EventOutboxJpaRepository outboxRepository;

    @Autowired
    private OutboxPollingPublisher outboxPollingPublisher;

    @Autowired
    private RabbitMQEventListener rabbitMQEventListener;

    @MockBean
    private RabbitTemplate rabbitTemplate;

    private RequestHmacSigner signer;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        outboxRepository.deleteAll();
        signer = new RequestHmacSigner(ACTIVE_KEY_ID, Map.of(ACTIVE_KEY_ID, GATEWAY_SECRET));

        // Default mock behavior for rabbitTemplate: acknowledge correlationData future
        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            if (cd != null) {
                cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            }
            return null;
        }).when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(String.class), any(CorrelationData.class));
    }

    private MockHttpServletRequestBuilder withHmac(MockHttpServletRequestBuilder builder,
                                                   String method,
                                                   String path,
                                                   String query,
                                                   String subject,
                                                   String userId,
                                                   String tenantId,
                                                   String roles,
                                                   byte[] body) {
        long timestamp = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        String bodyDigest = RequestHmacSigner.computeSha256Hex(body);
        String signature = signer.sign(
                ACTIVE_KEY_ID, method, path, query != null ? query : "",
                subject != null ? subject : "", userId != null ? userId : "",
                tenantId != null ? tenantId : "", roles != null ? roles : "",
                timestamp, nonce, bodyDigest
        );

        return builder
                .header(GatewaySecurityConstants.HEADER_GATEWAY_KEY_ID, ACTIVE_KEY_ID)
                .header(GatewaySecurityConstants.HEADER_GATEWAY_TIMESTAMP, String.valueOf(timestamp))
                .header(GatewaySecurityConstants.HEADER_GATEWAY_NONCE, nonce)
                .header(GatewaySecurityConstants.HEADER_GATEWAY_SIGNATURE, signature)
                .header(GatewaySecurityConstants.HEADER_GATEWAY_BODY_DIGEST, bodyDigest)
                .header(GatewaySecurityConstants.HEADER_USER_SUBJECT, subject)
                .header(GatewaySecurityConstants.HEADER_USER_ID, userId)
                .header(GatewaySecurityConstants.HEADER_TENANT_ID, tenantId)
                .header(GatewaySecurityConstants.HEADER_USER_ROLES, roles);
    }

    // =========================================================================
    // Priority 2 Tests: Request-Bound HMAC-SHA256 Gateway Authentication
    // =========================================================================

    @Test
    @DisplayName("P0-001 Gateway Bypass: Direct access lacking HMAC authentication headers returns RFC 7807 401")
    void testDirectAccessWithoutHmacHeadersReturns401() throws Exception {
        String eventPayload = "{\"eventType\":\"InvoiceProcessed\",\"eventSource\":\"document-ai\",\"payload\":\"{\\\"amount\\\":1000}\"}";

        mockMvc.perform(post("/api/v1/events/publish")
                        .header("X-Tenant-Id", "target-tenant")
                        .header("X-User-Subject", "attacker")
                        .header("X-User-Roles", "ROLE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventPayload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.type").value("https://fip.com/errors/unauthorized"))
                .andExpect(jsonPath("$.detail").value("Access denied: Missing internal service authentication headers. Request must pass through API Gateway."));
    }

    @Test
    @DisplayName("P0-001 Gateway Bypass: Request with forged HMAC signature returns RFC 7807 401")
    void testRequestWithForgedSignatureReturns401() throws Exception {
        String eventPayload = "{\"eventType\":\"InvoiceProcessed\",\"eventSource\":\"document-ai\",\"payload\":\"{\\\"amount\\\":1000}\"}";

        mockMvc.perform(post("/api/v1/events/publish")
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_KEY_ID, "v1")
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_TIMESTAMP, String.valueOf(System.currentTimeMillis()))
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_NONCE, UUID.randomUUID().toString())
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_SIGNATURE, "forged-invalid-hmac-signature-hex")
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_BODY_DIGEST, RequestHmacSigner.computeSha256Hex(eventPayload.getBytes()))
                        .header(GatewaySecurityConstants.HEADER_TENANT_ID, "target-tenant")
                        .header(GatewaySecurityConstants.HEADER_USER_SUBJECT, "attacker")
                        .header(GatewaySecurityConstants.HEADER_USER_ROLES, "ROLE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventPayload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Access denied: Request signature verification failed, expired, or replayed."));
    }

    @Test
    @DisplayName("P0-001 Gateway Bypass: Request with tampered body returns RFC 7807 401")
    void testRequestWithTamperedBodyReturns401() throws Exception {
        String originalPayload = "{\"eventType\":\"InvoiceProcessed\",\"eventSource\":\"document-ai\",\"payload\":\"{\\\"amount\\\":1000}\"}";
        String tamperedPayload = "{\"eventType\":\"InvoiceProcessed\",\"eventSource\":\"document-ai\",\"payload\":\"{\\\"amount\\\":9999999}\"}";

        long timestamp = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        String originalDigest = RequestHmacSigner.computeSha256Hex(originalPayload.getBytes());
        String signature = signer.sign("v1", "POST", "/api/v1/events/publish", "", "analyst", "u1", "tenant-alpha", "ROLE_ANALYST", timestamp, nonce, originalDigest);

        // Attacker sends tampered payload with original signature
        mockMvc.perform(post("/api/v1/events/publish")
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_KEY_ID, "v1")
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_TIMESTAMP, String.valueOf(timestamp))
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_NONCE, nonce)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_SIGNATURE, signature)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_BODY_DIGEST, originalDigest)
                        .header(GatewaySecurityConstants.HEADER_TENANT_ID, "tenant-alpha")
                        .header(GatewaySecurityConstants.HEADER_USER_SUBJECT, "analyst")
                        .header(GatewaySecurityConstants.HEADER_USER_ROLES, "ROLE_ANALYST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tamperedPayload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Access denied: Request body digest tampering detected."));
    }

    @Test
    @DisplayName("P0-001 Gateway Bypass: Request with expired timestamp (>60s) returns RFC 7807 401")
    void testRequestWithExpiredTimestampReturns401() throws Exception {
        String eventPayload = "{\"eventType\":\"InvoiceProcessed\",\"eventSource\":\"document-ai\",\"payload\":\"{\\\"amount\\\":1000}\"}";
        long expiredTimestamp = System.currentTimeMillis() - 70_000L; // 70 seconds ago
        String nonce = UUID.randomUUID().toString();
        String bodyDigest = RequestHmacSigner.computeSha256Hex(eventPayload.getBytes());
        String signature = signer.sign("v1", "POST", "/api/v1/events/publish", "", "analyst", "u1", "tenant-alpha", "ROLE_ANALYST", expiredTimestamp, nonce, bodyDigest);

        mockMvc.perform(post("/api/v1/events/publish")
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_KEY_ID, "v1")
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_TIMESTAMP, String.valueOf(expiredTimestamp))
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_NONCE, nonce)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_SIGNATURE, signature)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_BODY_DIGEST, bodyDigest)
                        .header(GatewaySecurityConstants.HEADER_TENANT_ID, "tenant-alpha")
                        .header(GatewaySecurityConstants.HEADER_USER_SUBJECT, "analyst")
                        .header(GatewaySecurityConstants.HEADER_USER_ROLES, "ROLE_ANALYST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventPayload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Access denied: Request signature verification failed, expired, or replayed."));
    }

    @Test
    @DisplayName("P0-001 Gateway Bypass: Replayed request with identical nonce returns RFC 7807 401")
    void testReplayedRequestWithIdenticalNonceReturns401() throws Exception {
        String eventPayload = "{\"eventType\":\"InvoiceProcessed\",\"eventSource\":\"document-ai\",\"payload\":\"{\\\"amount\\\":1000}\"}";
        long timestamp = System.currentTimeMillis();
        String reusedNonce = "replayed-nonce-" + UUID.randomUUID();
        String bodyDigest = RequestHmacSigner.computeSha256Hex(eventPayload.getBytes());
        String signature = signer.sign("v1", "POST", "/api/v1/events/publish", "", "analyst", "u1", "tenant-alpha", "ROLE_ANALYST", timestamp, reusedNonce, bodyDigest);

        // First request succeeds
        mockMvc.perform(post("/api/v1/events/publish")
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_KEY_ID, "v1")
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_TIMESTAMP, String.valueOf(timestamp))
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_NONCE, reusedNonce)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_SIGNATURE, signature)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_BODY_DIGEST, bodyDigest)
                        .header(GatewaySecurityConstants.HEADER_TENANT_ID, "tenant-alpha")
                        .header(GatewaySecurityConstants.HEADER_USER_SUBJECT, "analyst")
                        .header(GatewaySecurityConstants.HEADER_USER_ID, "u1")
                        .header(GatewaySecurityConstants.HEADER_USER_ROLES, "ROLE_ANALYST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventPayload))
                .andExpect(status().isCreated());

        // Replay attempt with same nonce fails
        mockMvc.perform(post("/api/v1/events/publish")
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_KEY_ID, "v1")
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_TIMESTAMP, String.valueOf(timestamp))
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_NONCE, reusedNonce)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_SIGNATURE, signature)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_BODY_DIGEST, bodyDigest)
                        .header(GatewaySecurityConstants.HEADER_TENANT_ID, "tenant-alpha")
                        .header(GatewaySecurityConstants.HEADER_USER_SUBJECT, "analyst")
                        .header(GatewaySecurityConstants.HEADER_USER_ID, "u1")
                        .header(GatewaySecurityConstants.HEADER_USER_ROLES, "ROLE_ANALYST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventPayload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Access denied: Request signature verification failed, expired, or replayed."));
    }

    // =========================================================================
    // Vertical Slice Functional & Multi-Tenant Isolation Tests
    // =========================================================================

    @Test
    @DisplayName("Vertical Slice: Happy Path (Ingest -> Persist -> Scope -> Retrieve)")
    void testEndToEndVerticalSliceSuccess() throws Exception {
        String eventPayload = """
                {
                    "eventType": "InvoiceProcessed",
                    "eventSource": "document-ai",
                    "payload": "{\\"invoiceId\\":\\"INV-9872\\",\\"amount\\":54000.00}",
                    "version": "1.0",
                    "metadata": {"source": "scanner"}
                }
                """;

        // Step 1: Ingest via POST with HMAC signature
        MockHttpServletRequestBuilder postReq = withHmac(
                post("/api/v1/events/publish"),
                "POST", "/api/v1/events/publish", "",
                "analyst@quantarth.com", UUID.randomUUID().toString(),
                "tenant-alpha", "ROLE_ANALYST", eventPayload.getBytes()
        ).contentType(MediaType.APPLICATION_JSON).content(eventPayload);

        MvcResult postResult = mockMvc.perform(postReq)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tenantId").value("tenant-alpha"))
                .andExpect(jsonPath("$.data.eventType").value("InvoiceProcessed"))
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(postResult.getResponse().getContentAsString());
        String eventIdStr = responseNode.path("data").path("eventId").asText();
        assertThat(eventIdStr).isNotEmpty();

        // Step 2: Retrieve event with matching Tenant context
        MockHttpServletRequestBuilder getReq = withHmac(
                get("/api/v1/events/" + eventIdStr),
                "GET", "/api/v1/events/" + eventIdStr, "",
                "analyst@quantarth.com", UUID.randomUUID().toString(),
                "tenant-alpha", "ROLE_ANALYST", new byte[0]
        );

        mockMvc.perform(getReq)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.eventId").value(eventIdStr))
                .andExpect(jsonPath("$.data.tenantId").value("tenant-alpha"));
    }

    @Test
    @DisplayName("Vertical Slice: Multi-Tenant Isolation - Cross-Tenant Access returns RFC 7807 404")
    void testCrossTenantIsolationReturns404ProblemDetail() throws Exception {
        String eventPayload = """
                {
                    "eventType": "TradeExecuted",
                    "eventSource": "arth-quant-ai",
                    "payload": "{\\"tradeId\\":\\"TRD-5501\\",\\"notional\\":1000000.00}",
                    "version": "1.0"
                }
                """;

        MockHttpServletRequestBuilder postReq = withHmac(
                post("/api/v1/events/publish"),
                "POST", "/api/v1/events/publish", "",
                "trader@tenantalpha.com", UUID.randomUUID().toString(),
                "tenant-alpha", "ROLE_ANALYST", eventPayload.getBytes()
        ).contentType(MediaType.APPLICATION_JSON).content(eventPayload);

        MvcResult postResult = mockMvc.perform(postReq)
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(postResult.getResponse().getContentAsString());
        String eventIdStr = responseNode.path("data").path("eventId").asText();

        // Step 2: Tenant Beta attempts to access Tenant Alpha's event -> Must return 404 Not Found
        MockHttpServletRequestBuilder getReq = withHmac(
                get("/api/v1/events/" + eventIdStr),
                "GET", "/api/v1/events/" + eventIdStr, "",
                "attacker@tenantbeta.com", UUID.randomUUID().toString(),
                "tenant-beta", "ROLE_ANALYST", new byte[0]
        );

        mockMvc.perform(getReq)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("https://fip.com/errors/not_found"))
                .andExpect(jsonPath("$.detail").value("Financial Event not found: " + eventIdStr));
    }

    @Test
    @DisplayName("Vertical Slice: RBAC Enforcement - Insufficient Roles return RFC 7807 403")
    void testRbacEnforcementReturns403ProblemDetail() throws Exception {
        String eventPayload = """
                {
                    "eventType": "SettlementInitiated",
                    "eventSource": "payment-gateway",
                    "payload": "{\\"settlementId\\":\\"SET-1100\\"}"
                }
                """;

        // Caller has ROLE_USER only, but POST /publish requires ROLE_ANALYST or ROLE_ADMIN
        MockHttpServletRequestBuilder postReq = withHmac(
                post("/api/v1/events/publish"),
                "POST", "/api/v1/events/publish", "",
                "viewer@tenantgamma.com", UUID.randomUUID().toString(),
                "tenant-gamma", "ROLE_USER", eventPayload.getBytes()
        ).contentType(MediaType.APPLICATION_JSON).content(eventPayload);

        mockMvc.perform(postReq)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.type").value("https://fip.com/errors/forbidden"));
    }

    // =========================================================================
    // Priority 3 Tests: Transactional Outbox Reliability
    // =========================================================================

    @Test
    @DisplayName("P0-003 Outbox: Publisher confirmations - record marked SENT only after broker ACK")
    void testPublisherConfirmationsVerifiedBrokerAck() throws Exception {
        String eventPayload = """
                {
                    "eventType": "PortfolioRebalanced",
                    "eventSource": "arth-quant-ai",
                    "payload": "{\\"rebalanceId\\":\\"REB-991\\",\\"status\\":\\"SUCCESS\\"}",
                    "version": "1.0"
                }
                """;

        MockHttpServletRequestBuilder postReq = withHmac(
                post("/api/v1/events/publish"),
                "POST", "/api/v1/events/publish", "",
                "analyst@quantarth.com", UUID.randomUUID().toString(),
                "tenant-alpha", "ROLE_ANALYST", eventPayload.getBytes()
        ).contentType(MediaType.APPLICATION_JSON).content(eventPayload);

        mockMvc.perform(postReq).andExpect(status().isCreated());

        // Verify outbox entry exists with status PENDING
        List<EventOutboxJpaEntity> pending = outboxRepository.findAll();
        assertThat(pending).hasSize(1);
        EventOutboxJpaEntity outbox = pending.get(0);
        assertThat(outbox.getStatus()).isEqualTo("PENDING");

        // Execute scheduled outbox publisher with broker ACK simulated
        int published = outboxPollingPublisher.publishPendingEvents();
        assertThat(published).isEqualTo(1);

        EventOutboxJpaEntity updated = outboxRepository.findById(outbox.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("SENT");
        assertThat(updated.getSentAt()).isNotNull();
        assertThat(updated.getLockedBy()).isNull();
        assertThat(updated.getLockedAt()).isNull();
    }

    @Test
    @DisplayName("P0-003 Outbox: Broker NACK triggers bounded retry and exponential backoff")
    void testBrokerNackTriggersExponentialBackoff() {
        // Create pending outbox entry directly
        EventOutboxJpaEntity entry = EventOutboxJpaEntity.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .eventType("PaymentFailed")
                .routingKey("events.payment")
                .payload("{\"paymentId\":\"P-101\"}")
                .status("PENDING")
                .createdAt(LocalDateTime.now())
                .build();
        outboxRepository.save(entry);

        // Configure RabbitTemplate mock to simulate Broker NACK
        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            if (cd != null) {
                cd.getFuture().complete(new CorrelationData.Confirm(false, "Exchange queue limit reached"));
            }
            return null;
        }).when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(String.class), any(CorrelationData.class));

        int published = outboxPollingPublisher.publishPendingEvents();
        assertThat(published).isEqualTo(0);

        EventOutboxJpaEntity updated = outboxRepository.findById(entry.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("PENDING");
        assertThat(updated.getRetryCount()).isEqualTo(1);
        assertThat(updated.getNextAttemptAt()).isAfter(LocalDateTime.now());
        assertThat(updated.getErrorMessage()).contains("Broker NACK");
    }

    @Test
    @DisplayName("P0-003 Outbox: Abandoned claims from crashed worker are recovered after timeout")
    void testAbandonedClaimRecovery() {
        // Record left in PROCESSING state by a crashed worker 90 seconds ago
        EventOutboxJpaEntity abandonedEntry = EventOutboxJpaEntity.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .eventType("RiskAlert")
                .routingKey("events.risk")
                .payload("{\"level\":\"CRITICAL\"}")
                .status("PROCESSING")
                .lockedBy("crashed-worker-hostname-99")
                .lockedAt(LocalDateTime.now().minusSeconds(90))
                .createdAt(LocalDateTime.now().minusSeconds(100))
                .build();
        outboxRepository.save(abandonedEntry);

        // Current worker polls and must recover and publish the abandoned entry
        int published = outboxPollingPublisher.publishPendingEvents();
        assertThat(published).isEqualTo(1);

        EventOutboxJpaEntity recovered = outboxRepository.findById(abandonedEntry.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo("SENT");
        assertThat(recovered.getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("P0-003 Outbox: Operational recovery can re-queue FAILED messages")
    void testOperationalRecoveryOfFailedMessages() {
        EventOutboxJpaEntity failedEntry = EventOutboxJpaEntity.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .eventType("AuditReport")
                .routingKey("events.audit")
                .payload("{\"reportId\":\"R-1\"}")
                .status("FAILED")
                .retryCount(5)
                .errorMessage("Max retries exceeded: network down")
                .createdAt(LocalDateTime.now())
                .build();
        outboxRepository.save(failedEntry);

        int recoveredCount = outboxPollingPublisher.recoverFailedRecords();
        assertThat(recoveredCount).isEqualTo(1);

        EventOutboxJpaEntity recovered = outboxRepository.findById(failedEntry.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo("PENDING");
        assertThat(recovered.getRetryCount()).isEqualTo(0);
        assertThat(recovered.getNextAttemptAt()).isNull();
        assertThat(recovered.getErrorMessage()).contains("Manually recovered by operator");
    }

    @Test
    @DisplayName("P0-003 Consumer Idempotency: Duplicate AMQP delivery is skipped without side effects")
    void testConsumerDurableIdempotencyOnDuplicateDelivery() throws Exception {
        UUID eventId = UUID.randomUUID();
        CanonicalEvent event = new CanonicalEvent(
                eventId,
                "SettlementProcessed",
                "payment-service",
                Instant.now(),
                "{\"settlementId\":\"S-99\"}",
                "1.0",
                UUID.randomUUID().toString(),
                "tenant-alpha",
                java.util.Collections.emptyMap()
        );
        String eventJson = objectMapper.writeValueAsString(event);

        // First delivery: processes and persists event
        rabbitMQEventListener.onEventReceived(eventJson);
        assertThat(repository.existsByEventId(eventId)).isTrue();
        long countAfterFirst = repository.count();

        // Second delivery (e.g. publisher crash recovery duplicate): skipped safely
        rabbitMQEventListener.onEventReceived(eventJson);
        long countAfterSecond = repository.count();
        assertThat(countAfterSecond).isEqualTo(countAfterFirst);
    }
}
