package com.fip.eventengine.compatibility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fip.eventengine.service.EventRoutingHelper;
import com.fip.shared.events.CanonicalEvent;
import com.fip.shared.events.EntityType;
import com.fip.shared.events.EventTopics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CompatibilityWireFormatTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("Phase 10: Legacy M2B event wire format deserializes cleanly into CanonicalEvent")
    void testLegacyM2BWireFormatDeserialization() throws Exception {
        // Actual M2B legacy fixture with old field names: eventSource, timestamp, traceId, version
        String legacyJson = """
                {
                    "eventId": "11111111-2222-3333-4444-555555555555",
                    "eventType": "InvoiceProcessed",
                    "eventSource": "document-ai-service",
                    "timestamp": "2026-03-15T10:30:00Z",
                    "payload": "{\\"invoiceId\\":\\"INV-1001\\",\\"amount\\":5000.00}",
                    "version": "1.0",
                    "traceId": "trace-legacy-001",
                    "tenantId": "tenant-corp",
                    "metadata": {
                        "scannerVersion": "v2.1"
                    }
                }
                """;

        CanonicalEvent canonical = objectMapper.readValue(legacyJson, CanonicalEvent.class);

        assertThat(canonical.eventId()).isEqualTo(UUID.fromString("11111111-2222-3333-4444-555555555555"));
        assertThat(canonical.eventType()).isEqualTo("InvoiceProcessed");
        // Backwards compatibility alias checks
        assertThat(canonical.sourceSystem()).isEqualTo("document-ai-service");
        assertThat(canonical.eventSource()).isEqualTo("document-ai-service");
        assertThat(canonical.occurredAt()).isEqualTo(Instant.parse("2026-03-15T10:30:00Z"));
        assertThat(canonical.timestamp()).isEqualTo(Instant.parse("2026-03-15T10:30:00Z"));
        assertThat(canonical.correlationId()).isEqualTo("trace-legacy-001");
        assertThat(canonical.traceId()).isEqualTo("trace-legacy-001");
        assertThat(canonical.schemaVersion()).isEqualTo("1.0");
        assertThat(canonical.version()).isEqualTo("1.0");
        assertThat(canonical.tenantId()).isEqualTo("tenant-corp");

        // Infer entity type for legacy events
        assertThat(canonical.entityType()).isEqualTo("INVOICE");

        // Routing key compatibility check
        String routingKey = EventRoutingHelper.resolveRoutingKey(canonical.entityType(), canonical.eventType());
        assertThat(routingKey).isEqualTo("financial.events.invoice.processed");
    }

    @Test
    @DisplayName("Phase 10: Canonical M3 financial event wire format deserializes cleanly with all metadata")
    void testCanonicalM3WireFormatDeserialization() throws Exception {
        String m3Json = """
                {
                    "eventId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                    "tenantId": "tenant-enterprise-99",
                    "schemaVersion": "1.0.0",
                    "eventType": "PaymentInitiated",
                    "entityType": "PAYMENT",
                    "entityId": "PAY-8821",
                    "sourceSystem": "core-payment-gateway",
                    "occurredAt": "2026-10-27T12:00:00Z",
                    "recordedAt": "2026-10-27T12:00:01Z",
                    "correlationId": "corr-uuid-99",
                    "causationId": "cause-uuid-11",
                    "idempotencyKey": "idem-key-8821",
                    "metadata": {
                        "environment": "production"
                    },
                    "payload": {
                        "paymentId": "PAY-8821",
                        "amount": {
                            "amount": 25000.7500,
                            "currency": "INR"
                        }
                    }
                }
                """;

        CanonicalEvent canonical = objectMapper.readValue(m3Json, CanonicalEvent.class);

        assertThat(canonical.eventId()).isEqualTo(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
        assertThat(canonical.tenantId()).isEqualTo("tenant-enterprise-99");
        assertThat(canonical.schemaVersion()).isEqualTo("1.0.0");
        assertThat(canonical.eventType()).isEqualTo("PaymentInitiated");
        assertThat(canonical.entityType()).isEqualTo("PAYMENT");
        assertThat(canonical.entityId()).isEqualTo("PAY-8821");
        assertThat(canonical.sourceSystem()).isEqualTo("core-payment-gateway");
        assertThat(canonical.occurredAt()).isEqualTo(Instant.parse("2026-10-27T12:00:00Z"));
        assertThat(canonical.recordedAt()).isEqualTo(Instant.parse("2026-10-27T12:00:01Z"));
        assertThat(canonical.correlationId()).isEqualTo("corr-uuid-99");
        assertThat(canonical.causationId()).isEqualTo("cause-uuid-11");
        assertThat(canonical.idempotencyKey()).isEqualTo("idem-key-8821");

        // Payload should be deserialized from structured JSON node to normalized JSON string
        JsonNode payloadNode = objectMapper.readTree(canonical.payload());
        assertThat(payloadNode.path("paymentId").asText()).isEqualTo("PAY-8821");
        assertThat(payloadNode.path("amount").path("currency").asText()).isEqualTo("INR");

        // Routing key check
        String routingKey = EventRoutingHelper.resolveRoutingKey(canonical.entityType(), canonical.eventType());
        assertThat(routingKey).isEqualTo("financial.events.payment.initiated");
    }

    @Test
    @DisplayName("Phase 10: Unknown optional fields in incoming wire format do not break deserialization")
    void testForwardCompatibilityUnknownFieldsIgnored() throws Exception {
        String forwardCompatibleJson = """
                {
                    "eventId": "22222222-3333-4444-5555-666666666666",
                    "eventType": "TaxFiled",
                    "sourceSystem": "tax-service",
                    "payload": "{\\"taxId\\":\\"TAX-1\\"}",
                    "unknownFutureField1": "someValue",
                    "futureNestedObject": {"extra": 123}
                }
                """;

        CanonicalEvent canonical = objectMapper.readValue(forwardCompatibleJson, CanonicalEvent.class);
        assertThat(canonical).isNotNull();
        assertThat(canonical.eventId()).isEqualTo(UUID.fromString("22222222-3333-4444-5555-666666666666"));
        assertThat(canonical.eventType()).isEqualTo("TaxFiled");
        assertThat(canonical.entityType()).isEqualTo("TAX");
    }

    @Test
    @DisplayName("Phase 10: Platform routing compatibility for Auth, Plugin, and AI events")
    void testPlatformRoutingCompatibility() {
        assertThat(EventRoutingHelper.resolveRoutingKey("AUTH", "UserLoggedIn"))
                .isEqualTo(EventTopics.ROUTING_KEY_AUTH);
        assertThat(EventRoutingHelper.resolveRoutingKey("PLUGIN", "PluginExecuted"))
                .isEqualTo(EventTopics.ROUTING_KEY_PLUGIN);
        assertThat(EventRoutingHelper.resolveRoutingKey("AI", "DocumentScanned"))
                .isEqualTo(EventTopics.ROUTING_KEY_AI);
        assertThat(EventRoutingHelper.resolveRoutingKey("PAYMENT", "PaymentInitiated"))
                .isEqualTo("financial.events.payment.initiated");
    }
}
