package com.fip.shared.model.financial.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fip.shared.model.financial.primitives.FinancialParty;
import com.fip.shared.model.financial.primitives.Money;
import com.fip.shared.model.financial.validation.FinancialValidationException;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Immutable Canonical Subscription domain model.
 * Represents recurring billing commitments, SaaS subscriptions, or retainer agreements.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Subscription(
        String subscriptionId,
        FinancialParty provider,
        FinancialParty customer,
        Money recurringAmount,
        String currency,
        BillingFrequency billingFrequency,
        LocalDate startDate,
        LocalDate endDate,
        SubscriptionStatus status
) implements Serializable {

    private static final long serialVersionUID = 1L;

    public Subscription {
        if (subscriptionId == null || subscriptionId.isBlank()) {
            throw new FinancialValidationException("subscriptionId cannot be null or blank");
        }
        if (provider == null) {
            throw new FinancialValidationException("Subscription provider cannot be null");
        }
        if (customer == null) {
            throw new FinancialValidationException("Subscription customer cannot be null");
        }
        if (recurringAmount == null) {
            throw new FinancialValidationException("Subscription recurringAmount cannot be null");
        }
        if (!recurringAmount.isPositive()) {
            throw new FinancialValidationException("Subscription recurringAmount must be strictly positive (> 0), got: " + recurringAmount);
        }

        if (currency == null || currency.isBlank()) {
            currency = recurringAmount.currency();
        } else if (!currency.equalsIgnoreCase(recurringAmount.currency())) {
            throw new FinancialValidationException(String.format(
                    "Subscription declared currency [%s] does not match recurringAmount currency [%s]",
                    currency, recurringAmount.currency()));
        }

        if (billingFrequency == null) {
            throw new FinancialValidationException("Subscription billingFrequency cannot be null");
        }
        if (startDate == null) {
            throw new FinancialValidationException("Subscription startDate cannot be null");
        }

        if (endDate != null && endDate.isBefore(startDate)) {
            throw new FinancialValidationException(String.format(
                    "Subscription endDate [%s] cannot precede startDate [%s]", endDate, startDate));
        }

        if (status == null) {
            status = SubscriptionStatus.ACTIVE;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String subscriptionId = UUID.randomUUID().toString();
        private FinancialParty provider;
        private FinancialParty customer;
        private Money recurringAmount;
        private String currency;
        private BillingFrequency billingFrequency = BillingFrequency.MONTHLY;
        private LocalDate startDate = LocalDate.now();
        private LocalDate endDate;
        private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

        public Builder subscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; return this; }
        public Builder provider(FinancialParty provider) { this.provider = provider; return this; }
        public Builder customer(FinancialParty customer) { this.customer = customer; return this; }
        public Builder recurringAmount(Money recurringAmount) {
            this.recurringAmount = recurringAmount;
            if (this.currency == null && recurringAmount != null) this.currency = recurringAmount.currency();
            return this;
        }
        public Builder currency(String currency) { this.currency = currency; return this; }
        public Builder billingFrequency(BillingFrequency billingFrequency) { this.billingFrequency = billingFrequency; return this; }
        public Builder startDate(LocalDate startDate) { this.startDate = startDate; return this; }
        public Builder endDate(LocalDate endDate) { this.endDate = endDate; return this; }
        public Builder status(SubscriptionStatus status) { this.status = status; return this; }

        public Subscription build() {
            return new Subscription(subscriptionId, provider, customer, recurringAmount, currency, billingFrequency, startDate, endDate, status);
        }
    }
}
