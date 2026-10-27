package com.fip.eventengine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.eventengine.domain.model.FinancialEvent;
import com.fip.eventengine.ports.out.EventStorePort;
import com.fip.shared.model.financial.domain.Payment;
import com.fip.shared.model.financial.domain.PaymentStatus;
import com.fip.shared.model.financial.domain.Refund;
import com.fip.shared.model.financial.domain.RefundStatus;
import com.fip.shared.model.financial.primitives.Money;
import com.fip.shared.model.financial.validation.FinancialModelValidator;
import com.fip.shared.model.financial.validation.FinancialValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Validates Phase 8 Refund Integrity & Concurrency Controls.
 * <p>
 * Enforces:
 * - Pessimistic row locking on target Payment record to prevent concurrent refund races.
 * - Individual refund amount <= captured payment amount.
 * - Cumulative pending and completed refunds <= captured payment amount (reserving capacity).
 * - Strict currency matching between refund and captured payment.
 * - Validation of payment refundability status (cannot refund CANCELLED/FAILED payments).
 * - Monotonic and valid state transitions for existing refund IDs.
 */
@Component
public class RefundIntegrityValidator {

    private static final Logger log = LoggerFactory.getLogger(RefundIntegrityValidator.class);

    private final EventStorePort eventStorePort;
    private final ObjectMapper objectMapper;

    public RefundIntegrityValidator(EventStorePort eventStorePort, ObjectMapper objectMapper) {
        this.eventStorePort = eventStorePort;
        this.objectMapper = objectMapper;
    }

    public void validateRefund(String tenantId, Refund refund) {
        // 1. Basic domain invariant validation
        FinancialModelValidator.validate(refund);

        // 2. Validate refund state transitions if this refundId was previously seen
        validateStateTransitions(tenantId, refund);

        // 3. Payment-level balance and currency verification with concurrency lock
        if (refund.originalPaymentReference() != null && refund.originalPaymentReference().value() != null) {
            String paymentId = refund.originalPaymentReference().value();

            // PESSIMISTIC WRITE LOCK: Serializes concurrent refunds targeting the same payment record
            Optional<FinancialEvent> paymentEventOpt = eventStorePort.findPaymentForUpdate(tenantId, paymentId);

            if (paymentEventOpt.isPresent()) {
                FinancialEvent paymentEvent = paymentEventOpt.get();
                Payment payment;
                try {
                    payment = objectMapper.readValue(paymentEvent.getPayload(), Payment.class);
                } catch (Exception e) {
                    log.error("Corrupted payment record payload for paymentId [{}]", paymentId, e);
                    throw new IllegalStateException("Corrupted payment record payload: " + paymentId, e);
                }

                // Currency Verification
                if (!payment.amount().currency().equalsIgnoreCase(refund.refundAmount().currency())) {
                    throw new FinancialValidationException(String.format(
                            "Refund currency [%s] does not match original payment currency [%s]",
                            refund.refundAmount().currency(), payment.amount().currency()));
                }

                // Payment Status Verification
                if (payment.status() == PaymentStatus.CANCELLED || payment.status() == PaymentStatus.FAILED) {
                    throw new FinancialValidationException(String.format(
                            "Cannot process refund against payment [%s] in terminal state [%s]",
                            paymentId, payment.status()));
                }

                // Individual refund limit
                if (refund.refundAmount().isGreaterThan(payment.amount())) {
                    throw new FinancialValidationException(String.format(
                            "Individual refund amount [%s] exceeds captured payment amount [%s]",
                            refund.refundAmount(), payment.amount()));
                }

                // Cumulative refund limit (including pending capacity reservation)
                List<FinancialEvent> existingRefundEvents = eventStorePort.findRefundsByPaymentId(tenantId, paymentId);
                Money cumulativeRefunded = Money.of(BigDecimal.ZERO, payment.amount().currency());

                for (FinancialEvent ev : existingRefundEvents) {
                    try {
                        Refund existing = objectMapper.readValue(ev.getPayload(), Refund.class);
                        // Skip failed or rejected refunds, and skip earlier versions of the exact same refundId
                        if (existing.status() != RefundStatus.FAILED
                                && existing.status() != RefundStatus.REJECTED
                                && !existing.refundId().equals(refund.refundId())) {
                            cumulativeRefunded = cumulativeRefunded.add(existing.refundAmount());
                        }
                    } catch (Exception ex) {
                        log.warn("Could not deserialize prior refund payload for event [{}]", ev.getEventId());
                    }
                }

                Money remainingCapacity = payment.amount().subtract(cumulativeRefunded);
                if (refund.refundAmount().isGreaterThan(remainingCapacity)) {
                    throw new FinancialValidationException(String.format(
                            "Cumulative refund amount exceeds payment capacity. Remaining capacity: [%s], requested refund: [%s] (already allocated: [%s])",
                            remainingCapacity, refund.refundAmount(), cumulativeRefunded));
                }

                log.info("Refund integrity verified for payment [{}]: requested [{}], remaining capacity after refund [{}]",
                        paymentId, refund.refundAmount(), remainingCapacity.subtract(refund.refundAmount()));

            } else {
                // If payment state is not yet present locally in persistence, document dependency & validate payload amount
                log.info("Payment record [{}] not found in tenant [{}] local persistence; validating against payload originalPaymentAmount",
                        paymentId, tenantId);
                if (refund.originalPaymentAmount() != null) {
                    if (!refund.originalPaymentAmount().currency().equalsIgnoreCase(refund.refundAmount().currency())) {
                        throw new FinancialValidationException(String.format(
                                "Original payment currency [%s] does not match refund currency [%s]",
                                refund.originalPaymentAmount().currency(), refund.refundAmount().currency()));
                    }
                    if (refund.refundAmount().isGreaterThan(refund.originalPaymentAmount())) {
                        throw new FinancialValidationException(String.format(
                                "Refund amount [%s] exceeds original payment amount [%s]",
                                refund.refundAmount(), refund.originalPaymentAmount()));
                    }
                }
            }
        }
    }

    private void validateStateTransitions(String tenantId, Refund newRefund) {
        List<FinancialEvent> priorEvents = eventStorePort.findRefundsByRefundId(tenantId, newRefund.refundId());
        if (priorEvents.isEmpty()) {
            return; // Initial refund record creation
        }

        try {
            Refund priorRefund = objectMapper.readValue(priorEvents.get(0).getPayload(), Refund.class);
            RefundStatus fromStatus = priorRefund.status();
            RefundStatus toStatus = newRefund.status();

            if (!fromStatus.canTransitionTo(toStatus)) {
                throw new FinancialValidationException(String.format(
                        "Invalid refund state transition: status [%s] cannot transition to [%s]",
                        fromStatus, toStatus));
            }

        } catch (FinancialValidationException fve) {
            throw fve;
        } catch (Exception e) {
            log.warn("Could not evaluate prior refund state for refundId [{}]", newRefund.refundId(), e);
        }
    }
}
