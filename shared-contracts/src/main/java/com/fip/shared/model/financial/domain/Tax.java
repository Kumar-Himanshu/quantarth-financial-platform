package com.fip.shared.model.financial.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fip.shared.model.financial.primitives.FinancialReference;
import com.fip.shared.model.financial.primitives.Money;
import com.fip.shared.model.financial.validation.FinancialValidationException;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Immutable Canonical Tax domain model.
 * Represents direct and indirect tax liabilities, withholdings (TDS/TCS), and refunds.
 * Negative amounts are deliberately permitted to express tax credits and refund receivables.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Tax(
        String taxRecordId,
        TaxType taxType,
        String jurisdiction,
        String taxPeriod,
        Money amount,
        String currency,
        LocalDate dueDate,
        TaxPaymentStatus paymentStatus,
        List<FinancialReference> references
) implements Serializable {

    private static final long serialVersionUID = 1L;

    public Tax {
        if (taxRecordId == null || taxRecordId.isBlank()) {
            throw new FinancialValidationException("taxRecordId cannot be null or blank");
        }
        if (taxType == null) {
            throw new FinancialValidationException("taxType cannot be null");
        }
        if (jurisdiction == null || jurisdiction.isBlank()) {
            throw new FinancialValidationException("jurisdiction cannot be null or blank");
        }
        if (taxPeriod == null || taxPeriod.isBlank()) {
            throw new FinancialValidationException("taxPeriod cannot be null or blank");
        }
        if (amount == null) {
            throw new FinancialValidationException("Tax amount cannot be null");
        }

        if (currency == null || currency.isBlank()) {
            currency = amount.currency();
        } else if (!currency.equalsIgnoreCase(amount.currency())) {
            throw new FinancialValidationException(String.format(
                    "Tax declared currency [%s] does not match amount currency [%s]",
                    currency, amount.currency()));
        }

        if (paymentStatus == null) {
            paymentStatus = amount.isNegative() ? TaxPaymentStatus.REFUND_DUE : TaxPaymentStatus.DUE;
        }

        references = references != null ? List.copyOf(references) : Collections.emptyList();
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isRefund() {
        return amount.isNegative() || paymentStatus == TaxPaymentStatus.REFUND_DUE || paymentStatus == TaxPaymentStatus.REFUNDED;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String taxRecordId = UUID.randomUUID().toString();
        private TaxType taxType;
        private String jurisdiction;
        private String taxPeriod;
        private Money amount;
        private String currency;
        private LocalDate dueDate;
        private TaxPaymentStatus paymentStatus;
        private List<FinancialReference> references = new ArrayList<>();

        public Builder taxRecordId(String taxRecordId) { this.taxRecordId = taxRecordId; return this; }
        public Builder taxType(TaxType taxType) { this.taxType = taxType; return this; }
        public Builder jurisdiction(String jurisdiction) { this.jurisdiction = jurisdiction; return this; }
        public Builder taxPeriod(String taxPeriod) { this.taxPeriod = taxPeriod; return this; }
        public Builder amount(Money amount) {
            this.amount = amount;
            if (this.currency == null && amount != null) this.currency = amount.currency();
            return this;
        }
        public Builder currency(String currency) { this.currency = currency; return this; }
        public Builder dueDate(LocalDate dueDate) { this.dueDate = dueDate; return this; }
        public Builder paymentStatus(TaxPaymentStatus paymentStatus) { this.paymentStatus = paymentStatus; return this; }
        public Builder references(List<FinancialReference> references) {
            this.references = references != null ? new ArrayList<>(references) : new ArrayList<>();
            return this;
        }
        public Builder addReference(FinancialReference reference) {
            if (reference != null) this.references.add(reference);
            return this;
        }

        public Tax build() {
            return new Tax(taxRecordId, taxType, jurisdiction, taxPeriod, amount, currency, dueDate, paymentStatus, references);
        }
    }
}
