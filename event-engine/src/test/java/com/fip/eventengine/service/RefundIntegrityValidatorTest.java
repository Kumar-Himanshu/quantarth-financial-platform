package com.fip.eventengine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fip.eventengine.domain.model.FinancialEvent;
import com.fip.eventengine.ports.out.EventStorePort;
import com.fip.shared.model.financial.domain.Payment;
import com.fip.shared.model.financial.domain.PaymentMethod;
import com.fip.shared.model.financial.domain.PaymentStatus;
import com.fip.shared.model.financial.domain.Refund;
import com.fip.shared.model.financial.domain.RefundStatus;
import com.fip.shared.model.financial.primitives.FinancialParty;
import com.fip.shared.model.financial.primitives.FinancialReference;
import com.fip.shared.model.financial.primitives.Money;
import com.fip.shared.model.financial.validation.FinancialValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefundIntegrityValidatorTest {

    @Mock
    private EventStorePort eventStorePort;

    private ObjectMapper objectMapper;
    private RefundIntegrityValidator validator;

    private final String tenantId = "tenant-alpha";
    private final String paymentId = "PAY-100";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        validator = new RefundIntegrityValidator(eventStorePort, objectMapper);
    }

    private Payment createPayment(String id, String amountStr, String currency, PaymentStatus status) {
        return Payment.builder()
                .paymentId(id)
                .amount(Money.of(new BigDecimal(amountStr), currency))
                .payer(FinancialParty.individual("USER-1", "Alice"))
                .payee(FinancialParty.merchant("MERCH-1", "Store"))
                .paymentMethod(PaymentMethod.CARD)
                .status(status)
                .build();
    }

    private Refund createRefund(String refundId, String paymentId, String amountStr, String currency, RefundStatus status) {
        return Refund.builder()
                .refundId(refundId)
                .originalPaymentReference(FinancialReference.paymentGateway(paymentId, "STRIPE"))
                .refundAmount(Money.of(new BigDecimal(amountStr), currency))
                .status(status)
                .reason("Customer return")
                .build();
    }

    private FinancialEvent createPaymentEvent(Payment payment) throws Exception {
        return FinancialEvent.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .eventType("PaymentCaptured")
                .entityType("PAYMENT")
                .entityId(payment.paymentId())
                .tenantId(tenantId)
                .eventSource("stripe")
                .sourceSystem("stripe")
                .payload(objectMapper.writeValueAsString(payment))
                .timestamp(LocalDateTime.now())
                .occurredAt(LocalDateTime.now())
                .processedAt(LocalDateTime.now())
                .recordedAt(LocalDateTime.now())
                .version("1.0.0")
                .build();
    }

    private FinancialEvent createRefundEvent(Refund refund) throws Exception {
        return FinancialEvent.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .eventType("RefundInitiated")
                .entityType("REFUND")
                .entityId(refund.refundId())
                .causationId(refund.originalPaymentReference().value())
                .tenantId(tenantId)
                .eventSource("stripe")
                .sourceSystem("stripe")
                .payload(objectMapper.writeValueAsString(refund))
                .timestamp(LocalDateTime.now())
                .occurredAt(LocalDateTime.now())
                .processedAt(LocalDateTime.now())
                .recordedAt(LocalDateTime.now())
                .version("1.0.0")
                .build();
    }

    @Test
    @DisplayName("Phase 8: Happy path - Refund amount within payment limit passes validation")
    void testRefundWithinPaymentCapacityPasses() throws Exception {
        Payment payment = createPayment(paymentId, "100.00", "USD", PaymentStatus.COMPLETED);
        FinancialEvent paymentEvent = createPaymentEvent(payment);

        when(eventStorePort.findPaymentForUpdate(tenantId, paymentId)).thenReturn(Optional.of(paymentEvent));
        when(eventStorePort.findRefundsByPaymentId(tenantId, paymentId)).thenReturn(Collections.emptyList());
        when(eventStorePort.findRefundsByRefundId(tenantId, "REF-1")).thenReturn(Collections.emptyList());

        Refund refund = createRefund("REF-1", paymentId, "40.00", "USD", RefundStatus.INITIATED);

        assertThatCode(() -> validator.validateRefund(tenantId, refund))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Phase 8: Currency mismatch between payment and refund throws FinancialValidationException")
    void testRefundCurrencyMismatchThrowsException() throws Exception {
        Payment payment = createPayment(paymentId, "100.00", "USD", PaymentStatus.COMPLETED);
        FinancialEvent paymentEvent = createPaymentEvent(payment);

        when(eventStorePort.findPaymentForUpdate(tenantId, paymentId)).thenReturn(Optional.of(paymentEvent));

        Refund refundEur = createRefund("REF-EUR", paymentId, "40.00", "EUR", RefundStatus.INITIATED);

        assertThatThrownBy(() -> validator.validateRefund(tenantId, refundEur))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("Refund currency [EUR] does not match original payment currency [USD]");
    }

    @Test
    @DisplayName("Phase 8: Payment in CANCELLED status rejects refund")
    void testRefundAgainstCancelledPaymentRejected() throws Exception {
        Payment payment = createPayment(paymentId, "100.00", "USD", PaymentStatus.CANCELLED);
        FinancialEvent paymentEvent = createPaymentEvent(payment);

        when(eventStorePort.findPaymentForUpdate(tenantId, paymentId)).thenReturn(Optional.of(paymentEvent));

        Refund refund = createRefund("REF-2", paymentId, "50.00", "USD", RefundStatus.INITIATED);

        assertThatThrownBy(() -> validator.validateRefund(tenantId, refund))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("Cannot process refund against payment [PAY-100] in terminal state [CANCELLED]");
    }

    @Test
    @DisplayName("Phase 8: Single refund exceeding captured payment amount is rejected")
    void testSingleRefundExceedingPaymentRejected() throws Exception {
        Payment payment = createPayment(paymentId, "100.00", "USD", PaymentStatus.COMPLETED);
        FinancialEvent paymentEvent = createPaymentEvent(payment);

        when(eventStorePort.findPaymentForUpdate(tenantId, paymentId)).thenReturn(Optional.of(paymentEvent));

        Refund refund = createRefund("REF-3", paymentId, "150.00", "USD", RefundStatus.INITIATED);

        assertThatThrownBy(() -> validator.validateRefund(tenantId, refund))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("exceeds captured payment amount");
    }

    @Test
    @DisplayName("Phase 8: Cumulative pending and completed refunds exceeding captured amount are rejected")
    void testCumulativeRefundsExceedingPaymentRejected() throws Exception {
        Payment payment = createPayment(paymentId, "100.00", "USD", PaymentStatus.COMPLETED);
        FinancialEvent paymentEvent = createPaymentEvent(payment);

        // Prior pending refund of $70
        Refund priorRefund = createRefund("REF-PRIOR", paymentId, "70.00", "USD", RefundStatus.PENDING);
        FinancialEvent priorRefundEvent = createRefundEvent(priorRefund);

        when(eventStorePort.findPaymentForUpdate(tenantId, paymentId)).thenReturn(Optional.of(paymentEvent));
        when(eventStorePort.findRefundsByPaymentId(tenantId, paymentId)).thenReturn(List.of(priorRefundEvent));
        when(eventStorePort.findRefundsByRefundId(tenantId, "REF-NEW")).thenReturn(Collections.emptyList());

        // New refund request of $40 ($70 + $40 = $110 > $100)
        Refund newRefund = createRefund("REF-NEW", paymentId, "40.00", "USD", RefundStatus.INITIATED);

        assertThatThrownBy(() -> validator.validateRefund(tenantId, newRefund))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("Cumulative refund amount exceeds payment capacity");
    }

    @Test
    @DisplayName("Phase 8: Terminal state COMPLETED cannot transition to INITIATED")
    void testTerminalRefundTransitionRejected() throws Exception {
        Refund priorCompleted = createRefund("REF-TERM", paymentId, "30.00", "USD", RefundStatus.COMPLETED);
        FinancialEvent priorEvent = createRefundEvent(priorCompleted);

        when(eventStorePort.findRefundsByRefundId(tenantId, "REF-TERM")).thenReturn(List.of(priorEvent));

        Refund attemptReopen = createRefund("REF-TERM", paymentId, "30.00", "USD", RefundStatus.INITIATED);

        assertThatThrownBy(() -> validator.validateRefund(tenantId, attemptReopen))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("Invalid refund state transition: status [COMPLETED] cannot transition to [INITIATED]");
    }

    @Test
    @DisplayName("Phase 8: External payment not in persistence validates against payload originalPaymentAmount")
    void testExternalPaymentValidatedAgainstPayloadAmount() {
        when(eventStorePort.findRefundsByRefundId(tenantId, "REF-EXT")).thenReturn(Collections.emptyList());
        when(eventStorePort.findPaymentForUpdate(tenantId, "PAY-EXT")).thenReturn(Optional.empty());

        // Valid refund under external payment original amount
        Refund validRefund = Refund.builder()
                .refundId("REF-EXT")
                .originalPaymentReference(FinancialReference.paymentGateway("PAY-EXT", "STRIPE"))
                .originalPaymentAmount(Money.of(new BigDecimal("100.00"), "USD"))
                .refundAmount(Money.of(new BigDecimal("80.00"), "USD"))
                .status(RefundStatus.INITIATED)
                .reason("Return")
                .build();

        assertThatCode(() -> validator.validateRefund(tenantId, validRefund))
                .doesNotThrowAnyException();

        // Refund builder rejects if refund amount exceeds originalPaymentAmount
        assertThatThrownBy(() -> Refund.builder()
                .refundId("REF-EXT-OVER")
                .originalPaymentReference(FinancialReference.paymentGateway("PAY-EXT", "STRIPE"))
                .originalPaymentAmount(Money.of(new BigDecimal("100.00"), "USD"))
                .refundAmount(Money.of(new BigDecimal("150.00"), "USD"))
                .status(RefundStatus.INITIATED)
                .reason("Return")
                .build())
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("cannot exceed original payment amount");
    }
}
