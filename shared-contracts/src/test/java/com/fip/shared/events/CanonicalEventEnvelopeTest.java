package com.fip.shared.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fip.shared.model.financial.domain.Payment;
import com.fip.shared.model.financial.domain.PaymentMethod;
import com.fip.shared.model.financial.domain.PaymentStatus;
import com.fip.shared.model.financial.primitives.FinancialParty;
import com.fip.shared.model.financial.primitives.FinancialReference;
import com.fip.shared.model.financial.primitives.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalEventEnvelopeTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("CanonicalEvent serializes all required metadata fields deterministically")
    void testCanonicalEventMetadataSerialization() throws Exception {
        UUID eventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Instant occurredAt = Instant.parse("2026-10-27T02:00:00Z");
        Instant recordedAt = Instant.parse("2026-10-27T02:00:01Z");

        CanonicalEvent event = CanonicalEvent.builder()
                .eventId(eventId)
                .tenantId("tenant-enterprise-1")
                .schemaVersion("1.0.0")
                .eventType("PaymentInitiated")
                .entityType(EntityType.PAYMENT)
                .entityId("PAY-7788")
                .sourceSystem("payment-gateway-service")
                .occurredAt(occurredAt)
                .recordedAt(recordedAt)
                .correlationId("corr-999")
                .causationId("cause-888")
                .idempotencyKey("idem-777")
                .metadata(Map.of("channel", "MOBILE_APP", "riskScore", "0.02"))
                .payload("{\"amount\":1500.00,\"currency\":\"INR\"}")
                .build();

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"eventId\":\"11111111-1111-1111-1111-111111111111\"");
        assertThat(json).contains("\"tenantId\":\"tenant-enterprise-1\"");
        assertThat(json).contains("\"schemaVersion\":\"1.0.0\"");
        assertThat(json).contains("\"eventType\":\"PaymentInitiated\"");
        assertThat(json).contains("\"entityType\":\"PAYMENT\"");
        assertThat(json).contains("\"entityId\":\"PAY-7788\"");
        assertThat(json).contains("\"sourceSystem\":\"payment-gateway-service\"");
        assertThat(json).contains("\"occurredAt\":\"2026-10-27T02:00:00Z\"");
        assertThat(json).contains("\"recordedAt\":\"2026-10-27T02:00:01Z\"");
        assertThat(json).contains("\"correlationId\":\"corr-999\"");
        assertThat(json).contains("\"causationId\":\"cause-888\"");
        assertThat(json).contains("\"idempotencyKey\":\"idem-777\"");
        assertThat(json).contains("\"payload\":\"{\\\"amount\\\":1500.00,\\\"currency\\\":\\\"INR\\\"}\"");

        // Deserialization check
        CanonicalEvent roundTrip = objectMapper.readValue(json, CanonicalEvent.class);
        assertThat(roundTrip.eventId()).isEqualTo(eventId);
        assertThat(roundTrip.entityType()).isEqualTo("PAYMENT");
        assertThat(roundTrip.sourceSystem()).isEqualTo("payment-gateway-service");
        assertThat(roundTrip.occurredAt()).isEqualTo(occurredAt);
        assertThat(roundTrip.correlationId()).isEqualTo("corr-999");
    }

    @Test
    @DisplayName("Backward Compatibility: Legacy M1/M2 JSON seamlessly deserializes with aliases")
    void testLegacyJsonDeserialization() throws Exception {
        String legacyJson = """
                {
                  "eventId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                  "eventType": "UserLoggedIn",
                  "eventSource": "auth-service",
                  "timestamp": "2026-10-27T02:15:00Z",
                  "payload": "{\\"userId\\":\\"user-99\\"}",
                  "version": "1.0",
                  "traceId": "trace-alpha-001",
                  "tenantId": "tenant-corp",
                  "metadata": {"ip": "192.168.1.1"}
                }
                """;

        CanonicalEvent parsed = objectMapper.readValue(legacyJson, CanonicalEvent.class);

        // Standard canonical fields populated from legacy aliases
        assertThat(parsed.eventId()).isEqualTo(UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890"));
        assertThat(parsed.eventType()).isEqualTo("UserLoggedIn");
        assertThat(parsed.sourceSystem()).isEqualTo("auth-service");
        assertThat(parsed.occurredAt()).isEqualTo(Instant.parse("2026-10-27T02:15:00Z"));
        assertThat(parsed.schemaVersion()).isEqualTo("1.0");
        assertThat(parsed.correlationId()).isEqualTo("trace-alpha-001");
        assertThat(parsed.tenantId()).isEqualTo("tenant-corp");
        assertThat(parsed.entityType()).isEqualTo("AUTH"); // Inferred entityType

        // Legacy accessors
        assertThat(parsed.eventSource()).isEqualTo("auth-service");
        assertThat(parsed.timestamp()).isEqualTo(Instant.parse("2026-10-27T02:15:00Z"));
        assertThat(parsed.traceId()).isEqualTo("trace-alpha-001");
        assertThat(parsed.version()).isEqualTo("1.0");
    }

    @Test
    @DisplayName("Backward Compatibility: Legacy 9-argument constructor preserves exact legacy behavior")
    void testLegacyConstructor() {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        CanonicalEvent legacy = new CanonicalEvent(
                eventId,
                "PluginExecuted",
                "plugin-framework",
                now,
                "{\"pluginId\":\"tax-calc\"}",
                "1.0",
                "trace-123",
                "tenant-xyz",
                Map.of("executionMs", 42)
        );

        assertThat(legacy.eventId()).isEqualTo(eventId);
        assertThat(legacy.eventType()).isEqualTo("PluginExecuted");
        assertThat(legacy.eventSource()).isEqualTo("plugin-framework");
        assertThat(legacy.timestamp()).isEqualTo(now);
        assertThat(legacy.version()).isEqualTo("1.0");
        assertThat(legacy.traceId()).isEqualTo("trace-123");
        assertThat(legacy.tenantId()).isEqualTo("tenant-xyz");
        assertThat(legacy.entityType()).isEqualTo("PLUGIN");
    }

    @Test
    @DisplayName("Forward Compatibility: Unknown future fields are ignored gracefully")
    void testUnknownFieldHandling() throws Exception {
        String futureJson = """
                {
                  "eventId": "22222222-2222-2222-2222-222222222222",
                  "eventType": "InvoiceIssued",
                  "sourceSystem": "billing-service",
                  "tenantId": "tenant-corp",
                  "schemaVersion": "1.1.0",
                  "futureExperimentalField": "some-value",
                  "aiInsightConfidence": 0.995,
                  "payload": "{}"
                }
                """;

        CanonicalEvent event = objectMapper.readValue(futureJson, CanonicalEvent.class);
        assertThat(event.eventId()).isEqualTo(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        assertThat(event.schemaVersion()).isEqualTo("1.1.0");
        assertThat(event.entityType()).isEqualTo("INVOICE");
    }

    @Test
    @DisplayName("CanonicalFinancialEvent: Strongly-typed generic envelope round-trip with domain payload")
    void testTypedEnvelopeRoundTrip() throws Exception {
        Payment payment = Payment.builder()
                .paymentId("pay-upi-999")
                .amount(Money.inr(new BigDecimal("499.00")))
                .payer(FinancialParty.individual("usr-p", "Priya Singh"))
                .payee(FinancialParty.merchant("mer-q", "QuickBites Store"))
                .paymentMethod(PaymentMethod.UPI)
                .status(PaymentStatus.COMPLETED)
                .addReference(FinancialReference.upi("330198765432"))
                .build();

        CanonicalFinancialEvent<Payment> typedEnvelope = CanonicalFinancialEvent.<Payment>builder()
                .eventId(UUID.randomUUID())
                .tenantId("tenant-retail-corp")
                .eventType("PaymentCompleted")
                .entityType(EntityType.PAYMENT)
                .entityId(payment.paymentId())
                .sourceSystem("upi-switch")
                .payload(payment)
                .build();

        // Convert to underlying CanonicalEvent for message bus transmission
        CanonicalEvent canonicalEvent = typedEnvelope.toCanonicalEvent(objectMapper);
        assertThat(canonicalEvent.entityType()).isEqualTo("PAYMENT");
        assertThat(canonicalEvent.entityId()).isEqualTo("pay-upi-999");
        assertThat(canonicalEvent.payload()).contains("\"amount\":499.00");

        // Convert back to typed envelope on consumer side
        CanonicalFinancialEvent<Payment> reconstructed = CanonicalFinancialEvent.fromCanonicalEvent(
                canonicalEvent, Payment.class, objectMapper);

        assertThat(reconstructed.payload().paymentId()).isEqualTo("pay-upi-999");
        assertThat(reconstructed.payload().amount()).isEqualTo(Money.inr(new BigDecimal("499.00")));
        assertThat(reconstructed.payload().paymentMethod()).isEqualTo(PaymentMethod.UPI);
        assertThat(reconstructed.payload().status()).isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    @DisplayName("Review 3 Golden File: Exact M2B RabbitMQ wire format with escaped JSON string payload")
    void testGoldenFileM2BWireFormatWithEscapedJsonStringPayload() throws Exception {
        // Actual historical event from M1/M2 RabbitMQ broker
        String m2bWireJson = """
                {
                  "eventId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
                  "eventType": "DocumentValidationJobCreated",
                  "eventSource": "document-ai-saas",
                  "timestamp": "2026-10-25T14:30:00Z",
                  "payload": "{\\"documentId\\":\\"doc-990\\",\\"amount\\":15000.0}",
                  "version": "1.0",
                  "traceId": "trace-12345",
                  "tenantId": "tenant-alpha"
                }
                """;

        CanonicalEvent event = objectMapper.readValue(m2bWireJson, CanonicalEvent.class);

        assertThat(event.eventId()).isEqualTo(UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479"));
        assertThat(event.eventType()).isEqualTo("DocumentValidationJobCreated");
        assertThat(event.sourceSystem()).isEqualTo("document-ai-saas");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-25T14:30:00Z"));
        assertThat(event.payload()).isEqualTo("{\"documentId\":\"doc-990\",\"amount\":15000.0}");
        assertThat(event.schemaVersion()).isEqualTo("1.0");
        assertThat(event.correlationId()).isEqualTo("trace-12345");
        assertThat(event.tenantId()).isEqualTo("tenant-alpha");

        // Verify legacy getters work unchanged for existing consumers
        assertThat(event.eventSource()).isEqualTo("document-ai-saas");
        assertThat(event.timestamp()).isEqualTo(Instant.parse("2026-10-25T14:30:00Z"));
        assertThat(event.traceId()).isEqualTo("trace-12345");
        assertThat(event.version()).isEqualTo("1.0");
    }

    @Test
    @DisplayName("Review 3 Golden File: Modern structured JSON object payload automatically normalized to string")
    void testGoldenFileStructuredObjectPayloadCompatibility() throws Exception {
        // Modern producers may emit a nested structured JSON object instead of an escaped string
        String modernWireJson = """
                {
                  "eventId": "55555555-5555-5555-5555-555555555555",
                  "tenantId": "tenant-fintech",
                  "eventType": "PaymentCaptured",
                  "entityType": "PAYMENT",
                  "entityId": "PAY-8821",
                  "sourceSystem": "payment-orchestrator",
                  "occurredAt": "2026-10-27T06:00:00Z",
                  "payload": {
                    "paymentId": "PAY-8821",
                    "amount": 2500.00,
                    "currency": "INR",
                    "status": "COMPLETED"
                  }
                }
                """;

        CanonicalEvent event = objectMapper.readValue(modernWireJson, CanonicalEvent.class);

        assertThat(event.eventId()).isEqualTo(UUID.fromString("55555555-5555-5555-5555-555555555555"));
        assertThat(event.entityType()).isEqualTo("PAYMENT");
        assertThat(event.entityId()).isEqualTo("PAY-8821");
        // Verify payload is cleanly parsed as a JSON string for database and legacy consumer compatibility
        assertThat(event.payload()).contains("\"paymentId\":\"PAY-8821\"");
        assertThat(event.payload()).contains("\"currency\":\"INR\"");
    }

    @Test
    @DisplayName("Review 4 Schema Versioning: Deserialization with missing optional fields")
    void testSchemaVersioningMissingOptionalFields() throws Exception {
        // Minimal valid event with missing optional fields: causationId, idempotencyKey, correlationId, metadata, recordedAt
        String minimalJson = """
                {
                  "eventId": "33333333-3333-3333-3333-333333333333",
                  "tenantId": "tenant-min",
                  "eventType": "AccountCreated",
                  "sourceSystem": "core-banking",
                  "occurredAt": "2026-10-27T08:00:00Z"
                }
                """;

        CanonicalEvent event = objectMapper.readValue(minimalJson, CanonicalEvent.class);

        assertThat(event.eventId()).isEqualTo(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        assertThat(event.schemaVersion()).isEqualTo("1.0.0"); // Defaulted
        assertThat(event.entityType()).isEqualTo(EntityType.OTHER.name()); // Inferred as OTHER from eventType
        assertThat(event.causationId()).isNull();
        assertThat(event.idempotencyKey()).isNull();
        assertThat(event.correlationId()).isNotNull(); // Distributed trace correlationId is auto-populated
        assertThat(event.metadata()).isEmpty();
        assertThat(event.recordedAt()).isNotNull();
    }

    @Test
    @DisplayName("Review 4 Schema Versioning: Enum evolution handles unknown entity types gracefully")
    void testEnumEvolutionUnknownEntityType() throws Exception {
        // Event with an entity type introduced in a future version (e.g. CRYPTO_WALLET or ESCROW)
        String futureEnumJson = """
                {
                  "eventId": "77777777-7777-7777-7777-777777777777",
                  "tenantId": "tenant-crypto",
                  "eventType": "EscrowLocked",
                  "entityType": "ESCROW_VAULT",
                  "sourceSystem": "escrow-service",
                  "schemaVersion": "2.0.0",
                  "occurredAt": "2026-10-27T09:00:00Z",
                  "payload": "{}"
                }
                """;

        CanonicalEvent event = objectMapper.readValue(futureEnumJson, CanonicalEvent.class);

        assertThat(event.entityType()).isEqualTo("ESCROW_VAULT");
        // Unknown enum does not crash the parser; string representation is safely preserved
        assertThat(EntityType.fromName(event.entityType())).isEqualTo(EntityType.OTHER);
    }
}
