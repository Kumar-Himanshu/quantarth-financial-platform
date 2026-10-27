package com.fip.eventengine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fip.common.exception.BadRequestException;
import com.fip.common.exception.ConflictException;
import com.fip.common.exception.ForbiddenException;
import com.fip.common.tenant.TenantContext;
import com.fip.eventengine.domain.model.FinancialEvent;
import com.fip.eventengine.ports.out.EventStorePort;
import com.fip.eventengine.ports.out.OutboxPort;
import com.fip.shared.events.CanonicalEvent;
import com.fip.shared.events.EntityType;
import com.fip.shared.model.financial.domain.Payment;
import com.fip.shared.model.financial.domain.PaymentMethod;
import com.fip.shared.model.financial.domain.PaymentStatus;
import com.fip.shared.model.financial.primitives.FinancialParty;
import com.fip.shared.model.financial.primitives.Money;
import com.fip.shared.model.financial.validation.FinancialValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CanonicalFinancialIngestionServiceTest {

    @Mock
    private EventStorePort eventStorePort;

    @Mock
    private OutboxPort outboxPort;

    @Mock
    private RefundIntegrityValidator refundIntegrityValidator;

    private ObjectMapper objectMapper;
    private EventIngestionService service;

    private final String tenantId = "tenant-fin-101";

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant(tenantId);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new EventIngestionService(eventStorePort, outboxPort, objectMapper, refundIntegrityValidator);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Payment createSamplePayment(String paymentId, String amount, String currency) {
        return Payment.builder()
                .paymentId(paymentId)
                .amount(Money.of(new BigDecimal(amount), currency))
                .payer(FinancialParty.individual("USER-1", "Alice Payer"))
                .payee(FinancialParty.merchant("MERCH-1", "Acme Store"))
                .paymentMethod(PaymentMethod.UPI)
                .status(PaymentStatus.COMPLETED)
                .build();
    }

    @Test
    @DisplayName("1. Ingest Canonical Payment: Valid payment persists and atomically writes to transactional outbox")
    void testIngestCanonicalPaymentSuccess() throws Exception {
        Payment payment = createSamplePayment("PAY-901", "1500.5000", "USD");
        String payloadJson = objectMapper.writeValueAsString(payment);

        CanonicalEvent incoming = new CanonicalEvent(
                UUID.randomUUID(),
                tenantId,
                "1.0.0",
                "PaymentInitiated",
                EntityType.PAYMENT.name(),
                payment.paymentId(),
                "payment-gateway",
                Instant.now(),
                Instant.now(),
                "corr-101",
                null,
                "idem-key-01",
                Map.of("channel", "web"),
                payloadJson
        );

        when(eventStorePort.findByIdempotencyKey(tenantId, "payment-gateway", "idem-key-01"))
                .thenReturn(Optional.empty());
        when(eventStorePort.save(any(FinancialEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        CanonicalEvent result = service.ingestCanonical(incoming);

        assertThat(result).isNotNull();
        assertThat(result.entityType()).isEqualTo("PAYMENT");
        assertThat(result.entityId()).isEqualTo("PAY-901");
        assertThat(result.tenantId()).isEqualTo(tenantId);
        assertThat(result.schemaVersion()).isEqualTo("1.0.0");

        // Verify outbox persistence with canonical routing key
        ArgumentCaptor<String> routingKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxPort).save(eq(result.eventId()), eq("PaymentInitiated"), routingKeyCaptor.capture(), anyString());
        assertThat(routingKeyCaptor.getValue()).isEqualTo("financial.events.payment.initiated");

        verify(eventStorePort).save(any(FinancialEvent.class));
    }

    @Test
    @DisplayName("2. Ingest Canonical: Invalid payload structure or negative amount throws FinancialValidationException")
    void testInvalidPayloadRejected() throws Exception {
        // Payment with non-positive amount
        String invalidPayload = """
                {
                    "paymentId": "PAY-BAD",
                    "amount": {"amount": -50.00, "currency": "USD"},
                    "payer": {"partyId": "P1", "partyType": "INDIVIDUAL", "name": "Bob"},
                    "payee": {"partyId": "P2", "partyType": "MERCHANT", "name": "Store"},
                    "paymentMethod": "UPI",
                    "status": "CAPTURED"
                }
                """;

        CanonicalEvent incoming = new CanonicalEvent(
                UUID.randomUUID(),
                tenantId,
                "1.0.0",
                "PaymentInitiated",
                EntityType.PAYMENT.name(),
                "PAY-BAD",
                "payment-service",
                Instant.now(),
                Instant.now(),
                "corr-bad",
                null,
                null,
                Collections.emptyMap(),
                invalidPayload
        );

        assertThatThrownBy(() -> service.ingestCanonical(incoming))
                .isInstanceOf(FinancialValidationException.class);
        verify(eventStorePort, never()).save(any());
        verify(outboxPort, never()).save(any(), any(), any(), any());
    }

    @Test
    @DisplayName("3. Monetary precision preservation: 4-decimal precision is preserved end-to-end")
    void testMonetaryPrecisionPreservation() throws Exception {
        BigDecimal highPrecisionAmount = new BigDecimal("12345.6789");
        Payment payment = createSamplePayment("PAY-PREC", highPrecisionAmount.toString(), "EUR");
        String payloadJson = objectMapper.writeValueAsString(payment);

        CanonicalEvent incoming = CanonicalEvent.builder()
                .eventType("PaymentCaptured")
                .entityType(EntityType.PAYMENT)
                .entityId(payment.paymentId())
                .sourceSystem("core-ledger")
                .payload(payloadJson)
                .build();

        when(eventStorePort.save(any(FinancialEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        CanonicalEvent result = service.ingestCanonical(incoming);

        Payment deserialized = objectMapper.readValue(result.payload(), Payment.class);
        assertThat(deserialized.amount().amount()).isEqualByComparingTo(highPrecisionAmount);
        assertThat(deserialized.amount().currency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("4. Tenant Isolation: Incoming event tenant is overridden with authenticated TenantContext")
    void testTenantContextEnforced() throws Exception {
        Payment payment = createSamplePayment("PAY-T1", "100.00", "USD");
        String payloadJson = objectMapper.writeValueAsString(payment);

        CanonicalEvent incoming = CanonicalEvent.builder()
                .tenantId("default") // Client passed default or omitted
                .eventType("PaymentCaptured")
                .entityType(EntityType.PAYMENT)
                .entityId(payment.paymentId())
                .sourceSystem("payment-gw")
                .payload(payloadJson)
                .build();

        when(eventStorePort.save(any(FinancialEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        CanonicalEvent result = service.ingestCanonical(incoming);

        assertThat(result.tenantId()).isEqualTo(tenantId);
    }

    @Test
    @DisplayName("5. Cross-Tenant Write Rejection: Client attempting to supply mismatched tenantId is rejected with 403 Forbidden")
    void testCrossTenantWriteRejected() throws Exception {
        Payment payment = createSamplePayment("PAY-T2", "100.00", "USD");
        String payloadJson = objectMapper.writeValueAsString(payment);

        CanonicalEvent incoming = CanonicalEvent.builder()
                .tenantId("tenant-victim-corp") // Attacker tries to inject into another tenant
                .eventType("PaymentCaptured")
                .entityType(EntityType.PAYMENT)
                .payload(payloadJson)
                .build();

        assertThatThrownBy(() -> service.ingestCanonical(incoming))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Cross-tenant write rejected");
    }

    @Test
    @DisplayName("6. Schema version validation: Unsupported version 2.0.0 throws BadRequestException")
    void testUnsupportedSchemaVersionRejected() throws Exception {
        Payment payment = createSamplePayment("PAY-VER", "100.00", "USD");
        String payloadJson = objectMapper.writeValueAsString(payment);

        CanonicalEvent incoming = CanonicalEvent.builder()
                .schemaVersion("2.0.0") // Unsupported version
                .eventType("PaymentCaptured")
                .entityType(EntityType.PAYMENT)
                .payload(payloadJson)
                .build();

        assertThatThrownBy(() -> service.ingestCanonical(incoming))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unsupported schema version: [2.0.0]");
    }

    @Test
    @DisplayName("7. Idempotency: Repeated identical request returns existing persisted event without duplicate insert")
    void testIdempotentDuplicateReturnsExistingEvent() throws Exception {
        Payment payment = createSamplePayment("PAY-IDEM", "250.00", "USD");
        String payloadJson = objectMapper.writeValueAsString(payment);
        String idemKey = "idem-unique-key-100";
        String source = "billing-engine";

        CanonicalEvent incoming = CanonicalEvent.builder()
                .idempotencyKey(idemKey)
                .sourceSystem(source)
                .eventType("PaymentCaptured")
                .entityType(EntityType.PAYMENT)
                .entityId(payment.paymentId())
                .payload(payloadJson)
                .build();

        FinancialEvent existingEntity = FinancialEvent.fromCanonicalEvent(incoming, "hash123");
        when(eventStorePort.findByIdempotencyKey(tenantId, source, idemKey))
                .thenReturn(Optional.of(existingEntity));

        CanonicalEvent result = service.ingestCanonical(incoming);

        assertThat(result).isNotNull();
        assertThat(result.eventId()).isEqualTo(existingEntity.getEventId());
        verify(eventStorePort, never()).save(any());
        verify(outboxPort, never()).save(any(), any(), any(), any());
    }

    @Test
    @DisplayName("8. Idempotency Conflict: Key reuse with differing payload throws ConflictException (409)")
    void testIdempotencyConflictThrowsConflictException() throws Exception {
        Payment payment1 = createSamplePayment("PAY-1", "100.00", "USD");
        Payment payment2 = createSamplePayment("PAY-2", "999.00", "USD"); // Different payload
        String idemKey = "reused-idem-key";
        String source = "billing-engine";

        CanonicalEvent firstEvent = CanonicalEvent.builder()
                .idempotencyKey(idemKey)
                .sourceSystem(source)
                .eventType("PaymentCaptured")
                .entityType(EntityType.PAYMENT)
                .entityId("PAY-1")
                .payload(objectMapper.writeValueAsString(payment1))
                .build();

        FinancialEvent existingInDb = FinancialEvent.fromCanonicalEvent(firstEvent, "hash_payment1");
        when(eventStorePort.findByIdempotencyKey(tenantId, source, idemKey))
                .thenReturn(Optional.of(existingInDb));

        CanonicalEvent conflictingIncoming = CanonicalEvent.builder()
                .idempotencyKey(idemKey)
                .sourceSystem(source)
                .eventType("PaymentCaptured")
                .entityType(EntityType.PAYMENT)
                .entityId("PAY-2")
                .payload(objectMapper.writeValueAsString(payment2)) // Different payload!
                .build();

        assertThatThrownBy(() -> service.ingestCanonical(conflictingIncoming))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Idempotency conflict: key 'reused-idem-key' was already used with a different payload");
    }

    @Test
    @DisplayName("9. Canonical Search with Bounded Pagination: Results are tenant-scoped and mapped to Page<CanonicalEvent>")
    void testSearchCanonicalEvents() throws Exception {
        Payment payment = createSamplePayment("PAY-SRCH", "50.00", "USD");
        FinancialEvent event = FinancialEvent.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .eventType("PaymentCaptured")
                .entityType("PAYMENT")
                .entityId(payment.paymentId())
                .tenantId(tenantId)
                .eventSource("payment-service")
                .sourceSystem("payment-service")
                .payload(objectMapper.writeValueAsString(payment))
                .timestamp(LocalDateTime.now())
                .occurredAt(LocalDateTime.now())
                .processedAt(LocalDateTime.now())
                .recordedAt(LocalDateTime.now())
                .version("1.0.0")
                .build();

        Page<FinancialEvent> page = new PageImpl<>(List.of(event), PageRequest.of(0, 10), 1);
        when(eventStorePort.searchCanonical(eq(tenantId), eq("PAYMENT"), eq(payment.paymentId()), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(page);

        Page<CanonicalEvent> results = service.searchCanonical(
                "PAYMENT", payment.paymentId(), null, null, null, null, PageRequest.of(0, 10)
        );

        assertThat(results.getTotalElements()).isEqualTo(1);
        assertThat(results.getContent().get(0).entityId()).isEqualTo(payment.paymentId());
        assertThat(results.getContent().get(0).tenantId()).isEqualTo(tenantId);
    }
}
