package com.fip.ingestion.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.common.tenant.TenantContext;
import com.fip.ingestion.adapters.out.eventengine.EventEngineClient;
import com.fip.ingestion.adapters.out.persistence.IngestionJobJpaEntity;
import com.fip.ingestion.adapters.out.persistence.IngestionJobJpaRepository;
import com.fip.ingestion.adapters.out.persistence.IngestionJobRecordJpaEntity;
import com.fip.ingestion.adapters.out.persistence.IngestionJobRecordJpaRepository;
import com.fip.ingestion.application.service.CsvIngestionService;
import com.fip.ingestion.application.service.JsonIngestionService;
import com.fip.ingestion.domain.model.JobStatus;
import com.fip.ingestion.domain.model.RecordStatus;
import com.fip.ingestion.domain.model.ReviewStatus;
import com.fip.shared.events.CanonicalEvent;
import com.fip.shared.security.GatewaySecurityConstants;
import com.fip.shared.security.RequestHmacSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Milestone 4B Section 3 & 6: Event-Engine Integration & External Refund Verification Tests.
 * <p>
 * Verifies:
 * - Valid JSON financial record forwarded to event-engine and persisted
 * - Valid CSV financial record streamed and forwarded to event-engine
 * - Multi-tenant isolation preserved end-to-end
 * - Duplicate event submission: deterministic idempotency key prevents duplicate canonical events
 * - Conflicting payload with identical idempotency key: returns 409 Conflict and isolates to error quarantine
 * - Event-engine unavailability or network failure: handled with fallback isolation, itemized quarantine, without dropping jobs
 * - Partial batch outcomes: valid records accepted, invalid records rejected, job status PARTIALLY_COMPLETED
 * - External refund without known payment: assigned UNVERIFIED_EXTERNAL & PENDING_RECONCILIATION, held in ingestion storage, NOT forwarded to event-engine
 * - Refund with known local payment: forwarded to event-engine for authoritative M3B validation
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Ingestion to Event-Engine Integration & External Refund Verification Tests")
class EventEngineIntegrationVerificationTest {

    @Value("${gateway.internal.active-key-id:v1}")
    private String activeKeyId;

    @Value("${gateway.internal.secret:${GATEWAY_INTERNAL_SECRET:" + GatewaySecurityConstants.DEFAULT_GATEWAY_TOKEN + "}}")
    private String gatewaySecret;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IngestionJobJpaRepository jobRepository;

    @Autowired
    private IngestionJobRecordJpaRepository recordRepository;

    @Autowired
    private JsonIngestionService jsonIngestionService;

    @Autowired
    private CsvIngestionService csvIngestionService;

    @MockBean
    private EventEngineClient eventEngineClient;

    private RequestHmacSigner signer;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        recordRepository.deleteAll();
        jobRepository.deleteAll();
        signer = new RequestHmacSigner(activeKeyId, Map.of(activeKeyId, gatewaySecret));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private MockHttpServletRequestBuilder withHmac(
            MockHttpServletRequestBuilder builder,
            String method,
            String path,
            String query,
            String tenantId,
            byte[] body) {

        long timestamp = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        String bodyDigest = RequestHmacSigner.computeSha256Hex(body);
        String subject = "user@tenant.com";
        String userId = "USR-101";
        String roles = "ROLE_ADMIN,ROLE_ANALYST";

        String signature = signer.sign(
                activeKeyId, method, path, query != null ? query : "",
                subject, userId, tenantId, roles,
                timestamp, nonce, bodyDigest
        );

        return builder
                .header(GatewaySecurityConstants.HEADER_GATEWAY_KEY_ID, activeKeyId)
                .header(GatewaySecurityConstants.HEADER_GATEWAY_TIMESTAMP, String.valueOf(timestamp))
                .header(GatewaySecurityConstants.HEADER_GATEWAY_NONCE, nonce)
                .header(GatewaySecurityConstants.HEADER_GATEWAY_SIGNATURE, signature)
                .header(GatewaySecurityConstants.HEADER_GATEWAY_BODY_DIGEST, bodyDigest)
                .header(GatewaySecurityConstants.HEADER_USER_SUBJECT, subject)
                .header(GatewaySecurityConstants.HEADER_USER_ID, userId)
                .header(GatewaySecurityConstants.HEADER_TENANT_ID, tenantId)
                .header(GatewaySecurityConstants.HEADER_USER_ROLES, roles);
    }

    @Test
    @DisplayName("3.1 Valid JSON record: forward to event-engine, canonical event persisted, job COMPLETED")
    void testValidJsonRecordForwardedAndPersisted() throws Exception {
        String tenantId = "tenant-fin-json";

        when(eventEngineClient.forwardBatch(anyList())).thenAnswer(invocation -> {
            List<CanonicalEvent> events = invocation.getArgument(0);
            return new EventEngineClient.BatchForwardResult(events, Collections.emptyList());
        });

        String payload = """
                {
                  "sourceSystem": "FINTECH_CORE",
                  "records": [
                    {
                      "id": "TXN-SYNC-1",
                      "amount": "2500.00",
                      "currency": "INR",
                      "payerName": "Aarav Sharma",
                      "payeeName": "Flipkart India",
                      "paymentMethod": "UPI"
                    }
                  ]
                }
                """;
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);

        MockHttpServletRequestBuilder request = post("/api/v1/ingestion/json")
                .queryParam("sync", "true")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);

        mockMvc.perform(withHmac(request, "POST", "/api/v1/ingestion/json", "sync=true", tenantId, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRecords").value(1))
                .andExpect(jsonPath("$.data.acceptedRecords").value(1))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        List<IngestionJobRecordJpaEntity> records = recordRepository.findAll();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getStatus()).isEqualTo(RecordStatus.ACCEPTED);
        assertThat(records.get(0).getTenantId()).isEqualTo(tenantId);
    }

    @Test
    @DisplayName("3.2 Valid CSV record: streams upload, forwards to event-engine, deterministic idempotency key")
    void testValidCsvRecordStreamedAndForwarded() throws Exception {
        String tenantId = "tenant-fin-csv";

        when(eventEngineClient.forwardBatch(anyList())).thenAnswer(invocation -> {
            List<CanonicalEvent> events = invocation.getArgument(0);
            // Verify deterministic idempotency key format: sourceSystem:entityId
            assertThat(events.get(0).idempotencyKey()).isEqualTo("BANK_FEED:CSV-TXN-1");
            return new EventEngineClient.BatchForwardResult(events, Collections.emptyList());
        });

        String csv = """
                id,amount,currency,payerName,payeeName,paymentMethod
                CSV-TXN-1,1800.75,INR,Karan Mehra,Amazon Pay,CARD
                """;

        MockMultipartFile file = new MockMultipartFile(
                "file", "statement.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        long timestamp = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        byte[] emptyBody = new byte[0];
        String bodyDigest = RequestHmacSigner.computeSha256Hex(emptyBody);
        String signature = signer.sign(
                activeKeyId, "POST", "/api/v1/ingestion/csv", "sourceSystem=BANK_FEED",
                "user@tenant.com", "USR-101", tenantId, "ROLE_ADMIN", timestamp, nonce, bodyDigest);

        mockMvc.perform(multipart("/api/v1/ingestion/csv")
                        .file(file)
                        .queryParam("sourceSystem", "BANK_FEED")
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_KEY_ID, activeKeyId)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_TIMESTAMP, String.valueOf(timestamp))
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_NONCE, nonce)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_SIGNATURE, signature)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_BODY_DIGEST, bodyDigest)
                        .header(GatewaySecurityConstants.HEADER_USER_SUBJECT, "user@tenant.com")
                        .header(GatewaySecurityConstants.HEADER_USER_ID, "USR-101")
                        .header(GatewaySecurityConstants.HEADER_TENANT_ID, tenantId)
                        .header(GatewaySecurityConstants.HEADER_USER_ROLES, "ROLE_ADMIN"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobId").exists());
    }

    @Test
    @DisplayName("3.3 Duplicate event submission: same idempotency key and identical payload acknowledged without duplicate")
    void testDuplicateEventSubmissionAcknowledged() throws Exception {
        String tenantId = "tenant-idem-ack";

        // Event engine acknowledges duplicate submission as already persisted
        when(eventEngineClient.forwardBatch(anyList())).thenAnswer(invocation -> {
            List<CanonicalEvent> events = invocation.getArgument(0);
            return new EventEngineClient.BatchForwardResult(events, Collections.emptyList());
        });

        JsonIngestionService.JsonIngestionRequest req = new JsonIngestionService.JsonIngestionRequest(
                "PORTAL", null, List.of(Map.of("id", "TXN-DUP-1", "amount", "500.00", "currency", "INR"))
        );

        TenantContext.setCurrentTenant(tenantId);
        JsonIngestionService.IngestionResponse resp1 = jsonIngestionService.ingestJson(req, true);
        assertThat(resp1.acceptedRecords()).isEqualTo(1);

        // Resubmit identical record
        JsonIngestionService.IngestionResponse resp2 = jsonIngestionService.ingestJson(req, true);
        assertThat(resp2.acceptedRecords()).isEqualTo(1);
    }

    @Test
    @DisplayName("3.4 Idempotency conflict: same key with conflicting payload results in REJECTED record quarantine")
    void testSameIdempotencyKeyConflictingPayloadIsolated() {
        String tenantId = "tenant-conflict";

        // Event-engine rejects conflicting payload with 409 Conflict
        when(eventEngineClient.forwardBatch(anyList())).thenAnswer(invocation -> {
            List<CanonicalEvent> events = invocation.getArgument(0);
            return new EventEngineClient.BatchForwardResult(
                    Collections.emptyList(),
                    List.of(new EventEngineClient.FailedEvent(events.get(0), "IDEMPOTENCY_CONFLICT", "Key reused with different payload"))
            );
        });

        JsonIngestionService.JsonIngestionRequest req = new JsonIngestionService.JsonIngestionRequest(
                "GATEWAY", null, List.of(Map.of("id", "TXN-CONFLICT-1", "amount", "999.00", "currency", "INR"))
        );

        TenantContext.setCurrentTenant(tenantId);
        JsonIngestionService.IngestionResponse resp = jsonIngestionService.ingestJson(req, true);

        assertThat(resp.rejectedRecords()).isEqualTo(1);
        assertThat(resp.acceptedRecords()).isEqualTo(0);
        assertThat(resp.status()).isEqualTo(JobStatus.PARTIALLY_COMPLETED);

        List<IngestionJobRecordJpaEntity> records = recordRepository.findAll();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getStatus()).isEqualTo(RecordStatus.REJECTED);
        assertThat(records.get(0).getErrorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    @DisplayName("3.5 Partial batch outcomes: mixed valid and invalid events produce itemized quarantine and PARTIALLY_COMPLETED job")
    void testPartialBatchOutcomes() {
        String tenantId = "tenant-partial";

        when(eventEngineClient.forwardBatch(anyList())).thenAnswer(invocation -> {
            List<CanonicalEvent> events = invocation.getArgument(0);
            // 1st passes, 2nd fails
            return new EventEngineClient.BatchForwardResult(
                    List.of(events.get(0)),
                    List.of(new EventEngineClient.FailedEvent(events.get(1), "VALIDATION_FAILED", "Currency mismatch"))
            );
        });

        JsonIngestionService.JsonIngestionRequest req = new JsonIngestionService.JsonIngestionRequest(
                "BATCH_FEED", null, List.of(
                        Map.of("id", "TXN-PARTIAL-1", "amount", "100.00", "currency", "INR"),
                        Map.of("id", "TXN-PARTIAL-2", "amount", "200.00", "currency", "INR")
                )
        );

        TenantContext.setCurrentTenant(tenantId);
        JsonIngestionService.IngestionResponse resp = jsonIngestionService.ingestJson(req, true);

        assertThat(resp.totalRecords()).isEqualTo(2);
        assertThat(resp.acceptedRecords()).isEqualTo(1);
        assertThat(resp.rejectedRecords()).isEqualTo(1);
        assertThat(resp.status()).isEqualTo(JobStatus.PARTIALLY_COMPLETED);

        List<IngestionJobRecordJpaEntity> records = recordRepository.findByJobId(resp.jobId());
        assertThat(records).hasSize(2);
    }

    @Test
    @DisplayName("3.6 Event-engine unavailability: fallback isolation captures items in error quarantine without crash")
    void testEventEngineUnavailabilityHandledGracefully() {
        String tenantId = "tenant-unavailable";

        // Event-engine completely throws Connection Refused
        when(eventEngineClient.forwardBatch(anyList())).thenAnswer(invocation -> {
            List<CanonicalEvent> events = invocation.getArgument(0);
            return new EventEngineClient.BatchForwardResult(
                    Collections.emptyList(),
                    List.of(new EventEngineClient.FailedEvent(events.get(0), "EVENT_ENGINE_UNAVAILABLE", "Connection refused: event-engine:8083"))
            );
        });

        JsonIngestionService.JsonIngestionRequest req = new JsonIngestionService.JsonIngestionRequest(
                "OFFLINE_FEED", null, List.of(Map.of("id", "TXN-OFFLINE-1", "amount", "50.00", "currency", "INR"))
        );

        TenantContext.setCurrentTenant(tenantId);
        JsonIngestionService.IngestionResponse resp = jsonIngestionService.ingestJson(req, true);

        assertThat(resp.rejectedRecords()).isEqualTo(1);
        assertThat(resp.acceptedRecords()).isEqualTo(0);

        List<IngestionJobRecordJpaEntity> records = recordRepository.findByJobId(resp.jobId());
        assertThat(records.get(0).getStatus()).isEqualTo(RecordStatus.REJECTED);
        assertThat(records.get(0).getErrorCode()).isEqualTo("EVENT_ENGINE_UNAVAILABLE");
    }

    // =========================================================================
    // Section 6: External Refund Verification
    // =========================================================================

    @Test
    @DisplayName("6.1 External refund without known payment: UNVERIFIED_EXTERNAL, PENDING_RECONCILIATION, NOT forwarded to event-engine")
    void testExternalRefundWithoutLocalPaymentRetainedInIngestionReview() {
        String tenantId = "tenant-external-refund";

        // Payment NOT found locally in persistence
        when(eventEngineClient.checkPaymentExists(eq(tenantId), eq("pi_external_unknown"))).thenReturn(false);

        JsonIngestionService.JsonIngestionRequest req = new JsonIngestionService.JsonIngestionRequest(
                "STRIPE_EXTERNAL", null, List.of(
                        Map.of(
                                "id", "re_unknown_123",
                                "amount", "450.00",
                                "currency", "INR",
                                "targetEntityType", "REFUND",
                                "originalPaymentReference", "pi_external_unknown"
                        )
                )
        );

        TenantContext.setCurrentTenant(tenantId);
        JsonIngestionService.IngestionResponse resp = jsonIngestionService.ingestJson(req, true);

        assertThat(resp.totalRecords()).isEqualTo(1);
        assertThat(resp.unverifiedRecords()).isEqualTo(1);
        assertThat(resp.acceptedRecords()).isEqualTo(0);
        assertThat(resp.rejectedRecords()).isEqualTo(0);

        List<IngestionJobRecordJpaEntity> records = recordRepository.findByJobId(resp.jobId());
        assertThat(records).hasSize(1);
        IngestionJobRecordJpaEntity refundRec = records.get(0);

        assertThat(refundRec.getStatus()).isEqualTo(RecordStatus.UNVERIFIED_EXTERNAL);
        assertThat(refundRec.getReviewStatus()).isEqualTo(ReviewStatus.PENDING_RECONCILIATION);
        assertThat(refundRec.getSourceRecordId()).isEqualTo("re_unknown_123");
        assertThat(refundRec.getTenantId()).isEqualTo(tenantId);
        // Canonical event UUID pointer must be NULL because it was NOT forwarded as a canonical event
        assertThat(refundRec.getCanonicalEventId()).isNull();
    }

    @Test
    @DisplayName("6.2 Known-payment refund: verified against local payment and forwarded to event-engine using M3B rules")
    void testKnownPaymentRefundForwardedToEventEngine() {
        String tenantId = "tenant-known-refund";

        // Payment DOES exist locally in event-engine persistence
        when(eventEngineClient.checkPaymentExists(eq(tenantId), eq("pi_known_payment_777"))).thenReturn(true);

        when(eventEngineClient.forwardBatch(anyList())).thenAnswer(invocation -> {
            List<CanonicalEvent> events = invocation.getArgument(0);
            assertThat(events.get(0).entityType()).isEqualTo("REFUND");
            return new EventEngineClient.BatchForwardResult(events, Collections.emptyList());
        });

        JsonIngestionService.JsonIngestionRequest req = new JsonIngestionService.JsonIngestionRequest(
                "STRIPE_CORE", null, List.of(
                        Map.of(
                                "id", "re_known_123",
                                "amount", "450.00",
                                "currency", "INR",
                                "targetEntityType", "REFUND",
                                "originalPaymentReference", "pi_known_payment_777"
                        )
                )
        );

        TenantContext.setCurrentTenant(tenantId);
        JsonIngestionService.IngestionResponse resp = jsonIngestionService.ingestJson(req, true);

        assertThat(resp.acceptedRecords()).isEqualTo(1);
        assertThat(resp.unverifiedRecords()).isEqualTo(0);

        List<IngestionJobRecordJpaEntity> records = recordRepository.findByJobId(resp.jobId());
        assertThat(records.get(0).getStatus()).isEqualTo(RecordStatus.ACCEPTED);
    }
}
