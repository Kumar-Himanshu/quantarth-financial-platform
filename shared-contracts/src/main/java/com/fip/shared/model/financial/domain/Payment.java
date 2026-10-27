package com.fip.shared.model.financial.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fip.shared.model.financial.primitives.AccountReference;
import com.fip.shared.model.financial.primitives.FinancialParty;
import com.fip.shared.model.financial.primitives.FinancialReference;
import com.fip.shared.model.financial.primitives.FinancialTime;
import com.fip.shared.model.financial.primitives.Money;
import com.fip.shared.model.financial.validation.FinancialValidationException;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Immutable Canonical Payment domain model.
 * Represents commercial payments, UPI transfers, card settlements, etc.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Payment(
        String paymentId,
        Money amount,
        FinancialParty payer,
        FinancialParty payee,
        PaymentMethod paymentMethod,
        PaymentStatus status,
        FinancialTime timestamp,
        List<FinancialReference> references,
        AccountReference payerAccount,
        AccountReference payeeAccount
) implements Serializable {

    private static final long serialVersionUID = 1L;

    public Payment {
        if (paymentId == null || paymentId.isBlank()) {
            throw new FinancialValidationException("paymentId cannot be null or blank");
        }
        if (amount == null) {
            throw new FinancialValidationException("Payment amount cannot be null");
        }
        if (!amount.isPositive()) {
            throw new FinancialValidationException("Payment amount must be strictly positive (> 0), got: " + amount);
        }
        if (payer == null) {
            throw new FinancialValidationException("Payment payer cannot be null");
        }
        if (payee == null) {
            throw new FinancialValidationException("Payment payee cannot be null");
        }
        if (paymentMethod == null) {
            throw new FinancialValidationException("Payment paymentMethod cannot be null");
        }
        if (status == null) {
            throw new FinancialValidationException("Payment status cannot be null");
        }
        if (timestamp == null) {
            timestamp = FinancialTime.now();
        }
        references = references != null ? List.copyOf(references) : Collections.emptyList();
    }

    public Payment transitionTo(PaymentStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw new FinancialValidationException(String.format(
                    "Illegal payment state transition from [%s] to [%s] for payment [%s]",
                    this.status, target, this.paymentId));
        }
        return new Payment(
                this.paymentId,
                this.amount,
                this.payer,
                this.payee,
                this.paymentMethod,
                target,
                FinancialTime.now(),
                this.references,
                this.payerAccount,
                this.payeeAccount
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String paymentId = UUID.randomUUID().toString();
        private Money amount;
        private FinancialParty payer;
        private FinancialParty payee;
        private PaymentMethod paymentMethod;
        private PaymentStatus status = PaymentStatus.INITIATED;
        private FinancialTime timestamp = FinancialTime.now();
        private List<FinancialReference> references = new ArrayList<>();
        private AccountReference payerAccount;
        private AccountReference payeeAccount;

        public Builder paymentId(String paymentId) { this.paymentId = paymentId; return this; }
        public Builder amount(Money amount) { this.amount = amount; return this; }
        public Builder payer(FinancialParty payer) { this.payer = payer; return this; }
        public Builder payee(FinancialParty payee) { this.payee = payee; return this; }
        public Builder paymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; return this; }
        public Builder status(PaymentStatus status) { this.status = status; return this; }
        public Builder timestamp(FinancialTime timestamp) { this.timestamp = timestamp; return this; }
        public Builder references(List<FinancialReference> references) {
            this.references = references != null ? new ArrayList<>(references) : new ArrayList<>();
            return this;
        }
        public Builder addReference(FinancialReference reference) {
            if (reference != null) this.references.add(reference);
            return this;
        }
        public Builder payerAccount(AccountReference payerAccount) { this.payerAccount = payerAccount; return this; }
        public Builder payeeAccount(AccountReference payeeAccount) { this.payeeAccount = payeeAccount; return this; }

        public Payment build() {
            return new Payment(paymentId, amount, payer, payee, paymentMethod, status, timestamp, references, payerAccount, payeeAccount);
        }
    }
}
