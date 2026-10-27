package com.fip.shared.model.financial.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fip.shared.model.financial.primitives.AccountReference;
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
 * Immutable Canonical Transfer domain model.
 * Represents intra-institution or inter-account liquidity movements between accounts.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Transfer(
        String transferId,
        AccountReference sourceAccount,
        AccountReference destinationAccount,
        Money amount,
        TransferStatus status,
        FinancialTime timestamp,
        List<FinancialReference> references
) implements Serializable {

    private static final long serialVersionUID = 1L;

    public Transfer {
        if (transferId == null || transferId.isBlank()) {
            throw new FinancialValidationException("transferId cannot be null or blank");
        }
        if (sourceAccount == null) {
            throw new FinancialValidationException("sourceAccount cannot be null");
        }
        if (destinationAccount == null) {
            throw new FinancialValidationException("destinationAccount cannot be null");
        }
        if (sourceAccount.accountId().equalsIgnoreCase(destinationAccount.accountId())) {
            throw new FinancialValidationException(
                    "Transfer sourceAccount and destinationAccount cannot be identical");
        }
        if (amount == null) {
            throw new FinancialValidationException("Transfer amount cannot be null");
        }
        if (!amount.isPositive()) {
            throw new FinancialValidationException("Transfer amount must be strictly positive (> 0), got: " + amount);
        }
        if (status == null) {
            throw new FinancialValidationException("Transfer status cannot be null");
        }
        if (timestamp == null) {
            timestamp = FinancialTime.now();
        }
        references = references != null ? List.copyOf(references) : Collections.emptyList();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String transferId = UUID.randomUUID().toString();
        private AccountReference sourceAccount;
        private AccountReference destinationAccount;
        private Money amount;
        private TransferStatus status = TransferStatus.INITIATED;
        private FinancialTime timestamp = FinancialTime.now();
        private List<FinancialReference> references = new ArrayList<>();

        public Builder transferId(String transferId) { this.transferId = transferId; return this; }
        public Builder sourceAccount(AccountReference sourceAccount) { this.sourceAccount = sourceAccount; return this; }
        public Builder destinationAccount(AccountReference destinationAccount) { this.destinationAccount = destinationAccount; return this; }
        public Builder amount(Money amount) { this.amount = amount; return this; }
        public Builder status(TransferStatus status) { this.status = status; return this; }
        public Builder timestamp(FinancialTime timestamp) { this.timestamp = timestamp; return this; }
        public Builder references(List<FinancialReference> references) {
            this.references = references != null ? new ArrayList<>(references) : new ArrayList<>();
            return this;
        }
        public Builder addReference(FinancialReference reference) {
            if (reference != null) this.references.add(reference);
            return this;
        }

        public Transfer build() {
            return new Transfer(transferId, sourceAccount, destinationAccount, amount, status, timestamp, references);
        }
    }
}
