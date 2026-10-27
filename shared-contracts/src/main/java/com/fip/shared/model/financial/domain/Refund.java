package com.fip.shared.model.financial.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fip.shared.model.financial.primitives.FinancialReference;
import com.fip.shared.model.financial.primitives.FinancialTime;
import com.fip.shared.model.financial.primitives.Money;
import com.fip.shared.model.financial.validation.FinancialValidationException;

import java.io.Serializable;
import java.util.UUID;

/**
 * Immutable Canonical Refund domain model.
 * <p>
 * <b>Individual vs Cumulative Validation:</b><br>
 * At the individual entity boundary, this record validates that {@code refundAmount > 0} and
 * {@code refundAmount <= originalCapturedAmount} (or {@code originalPaymentAmount}).
 * Cross-transaction cumulative enforcement (guaranteeing that {@code sum(completed refunds) <= captured amount}
 * across concurrent partial refund requests, reversals, and status updates) is orchestrated at the aggregate service
 * level in M3B via database row leasing or atomic conditional updates.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Refund(
        String refundId,
        FinancialReference originalPaymentReference,
        Money originalPaymentAmount,
        Money originalCapturedAmount,
        Money refundAmount,
        Money cumulativeRefundAmount,
        String currency,
        RefundStatus status,
        FinancialTime timestamp,
        String reason
) implements Serializable {

    private static final long serialVersionUID = 1L;

    public Refund {
        if (refundId == null || refundId.isBlank()) {
            throw new FinancialValidationException("refundId cannot be null or blank");
        }
        if (originalPaymentReference == null) {
            throw new FinancialValidationException("originalPaymentReference cannot be null");
        }
        if (refundAmount == null) {
            throw new FinancialValidationException("refundAmount cannot be null");
        }
        if (!refundAmount.isPositive()) {
            throw new FinancialValidationException("refundAmount must be strictly positive (> 0), got: " + refundAmount);
        }

        if (currency == null || currency.isBlank()) {
            currency = refundAmount.currency();
        } else if (!currency.equalsIgnoreCase(refundAmount.currency())) {
            throw new FinancialValidationException(String.format(
                    "Declared currency [%s] does not match refundAmount currency [%s]",
                    currency, refundAmount.currency()));
        }

        // Determine effective payment ceiling (captured amount takes precedence over authorized amount)
        Money effectiveCeiling = originalCapturedAmount != null ? originalCapturedAmount : originalPaymentAmount;
        if (effectiveCeiling != null) {
            effectiveCeiling.validateSameCurrency(refundAmount);
            if (refundAmount.isGreaterThan(effectiveCeiling)) {
                throw new FinancialValidationException(String.format(
                        "Refund amount [%s] cannot exceed original payment amount [%s]",
                        refundAmount, effectiveCeiling));
            }
        }

        // Cumulative check if cumulative sum is known
        if (cumulativeRefundAmount != null) {
            refundAmount.validateSameCurrency(cumulativeRefundAmount);
            Money totalCumulative = cumulativeRefundAmount.add(refundAmount);
            if (effectiveCeiling != null && totalCumulative.isGreaterThan(effectiveCeiling)) {
                throw new FinancialValidationException(String.format(
                        "Cumulative refunds [%s] cannot exceed original captured payment amount [%s]",
                        totalCumulative, effectiveCeiling));
            }
        }

        if (status == null) {
            status = RefundStatus.INITIATED;
        }
        if (timestamp == null) {
            timestamp = FinancialTime.now();
        }
    }

    /**
     * Legacy constructor preserving 8-parameter signature from initial M3A implementation.
     */
    public Refund(
            String refundId,
            FinancialReference originalPaymentReference,
            Money originalPaymentAmount,
            Money refundAmount,
            String currency,
            RefundStatus status,
            FinancialTime timestamp,
            String reason
    ) {
        this(refundId, originalPaymentReference, originalPaymentAmount, originalPaymentAmount,
                refundAmount, null, currency, status, timestamp, reason);
    }

    public boolean canTransitionTo(RefundStatus target) {
        return this.status != null && this.status.canTransitionTo(target);
    }

    public Refund transitionTo(RefundStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw new FinancialValidationException(String.format(
                    "Illegal refund state transition from %s to %s for refund %s",
                    this.status, target, this.refundId));
        }
        return new Refund(
                this.refundId,
                this.originalPaymentReference,
                this.originalPaymentAmount,
                this.originalCapturedAmount,
                this.refundAmount,
                this.cumulativeRefundAmount,
                this.currency,
                target,
                FinancialTime.now(),
                this.reason
        );
    }

    @JsonIgnore
    public boolean isPartialRefund() {
        Money effectiveCeiling = originalCapturedAmount != null ? originalCapturedAmount : originalPaymentAmount;
        return effectiveCeiling != null && refundAmount.isLessThan(effectiveCeiling);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String refundId = UUID.randomUUID().toString();
        private FinancialReference originalPaymentReference;
        private Money originalPaymentAmount;
        private Money originalCapturedAmount;
        private Money refundAmount;
        private Money cumulativeRefundAmount;
        private String currency;
        private RefundStatus status = RefundStatus.INITIATED;
        private FinancialTime timestamp = FinancialTime.now();
        private String reason;

        public Builder refundId(String refundId) { this.refundId = refundId; return this; }
        public Builder originalPaymentReference(FinancialReference originalPaymentReference) {
            this.originalPaymentReference = originalPaymentReference;
            return this;
        }
        public Builder originalPaymentAmount(Money originalPaymentAmount) {
            this.originalPaymentAmount = originalPaymentAmount;
            return this;
        }
        public Builder originalCapturedAmount(Money originalCapturedAmount) {
            this.originalCapturedAmount = originalCapturedAmount;
            return this;
        }
        public Builder refundAmount(Money refundAmount) {
            this.refundAmount = refundAmount;
            if (this.currency == null && refundAmount != null) {
                this.currency = refundAmount.currency();
            }
            return this;
        }
        public Builder cumulativeRefundAmount(Money cumulativeRefundAmount) {
            this.cumulativeRefundAmount = cumulativeRefundAmount;
            return this;
        }
        public Builder currency(String currency) { this.currency = currency; return this; }
        public Builder status(RefundStatus status) { this.status = status; return this; }
        public Builder timestamp(FinancialTime timestamp) { this.timestamp = timestamp; return this; }
        public Builder reason(String reason) { this.reason = reason; return this; }

        public Refund build() {
            return new Refund(refundId, originalPaymentReference, originalPaymentAmount, originalCapturedAmount,
                    refundAmount, cumulativeRefundAmount, currency, status, timestamp, reason);
        }
    }
}
