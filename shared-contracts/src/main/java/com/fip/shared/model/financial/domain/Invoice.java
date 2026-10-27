package com.fip.shared.model.financial.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fip.shared.model.financial.primitives.FinancialParty;
import com.fip.shared.model.financial.primitives.Money;
import com.fip.shared.model.financial.validation.FinancialValidationException;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Immutable Canonical Invoice domain model.
 * Represents commercial receivables and payable billings with subtotal/tax consistency.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Invoice(
        String invoiceId,
        FinancialParty issuer,
        FinancialParty customer,
        String invoiceNumber,
        LocalDate issueDate,
        LocalDate dueDate,
        Money subtotal,
        Money taxAmount,
        Money totalAmount,
        String currency,
        InvoicePaymentStatus paymentStatus,
        Money paidAmount
) implements Serializable {

    private static final long serialVersionUID = 1L;

    public Invoice {
        if (invoiceId == null || invoiceId.isBlank()) {
            throw new FinancialValidationException("invoiceId cannot be null or blank");
        }
        if (issuer == null) {
            throw new FinancialValidationException("Invoice issuer cannot be null");
        }
        if (customer == null) {
            throw new FinancialValidationException("Invoice customer cannot be null");
        }
        if (invoiceNumber == null || invoiceNumber.isBlank()) {
            throw new FinancialValidationException("Invoice invoiceNumber cannot be null or blank");
        }
        if (issueDate == null) {
            throw new FinancialValidationException("Invoice issueDate cannot be null");
        }
        if (dueDate == null) {
            throw new FinancialValidationException("Invoice dueDate cannot be null");
        }
        if (dueDate.isBefore(issueDate)) {
            throw new FinancialValidationException(String.format(
                    "Invoice dueDate [%s] cannot precede issueDate [%s]", dueDate, issueDate));
        }

        if (subtotal == null) {
            throw new FinancialValidationException("Invoice subtotal cannot be null");
        }
        if (subtotal.isNegative()) {
            throw new FinancialValidationException("Invoice subtotal cannot be negative, got: " + subtotal);
        }

        if (totalAmount == null) {
            throw new FinancialValidationException("Invoice totalAmount cannot be null");
        }

        if (currency == null || currency.isBlank()) {
            currency = totalAmount.currency();
        } else if (!currency.equalsIgnoreCase(totalAmount.currency())) {
            throw new FinancialValidationException(String.format(
                    "Invoice declared currency [%s] does not match totalAmount currency [%s]",
                    currency, totalAmount.currency()));
        }

        subtotal.validateSameCurrency(totalAmount);

        if (taxAmount != null) {
            totalAmount.validateSameCurrency(taxAmount);
            if (taxAmount.isNegative()) {
                throw new FinancialValidationException("Invoice taxAmount cannot be negative, got: " + taxAmount);
            }
            Money expectedTotal = subtotal.add(taxAmount);
            if (!totalAmount.equals(expectedTotal)) {
                throw new FinancialValidationException(String.format(
                        "Invoice totalAmount [%s] does not equal subtotal [%s] + taxAmount [%s] (expected [%s])",
                        totalAmount, subtotal, taxAmount, expectedTotal));
            }
        }

        if (paidAmount != null) {
            totalAmount.validateSameCurrency(paidAmount);
            if (paidAmount.isNegative()) {
                throw new FinancialValidationException("Invoice paidAmount cannot be negative, got: " + paidAmount);
            }
            if (paidAmount.isGreaterThan(totalAmount)) {
                throw new FinancialValidationException(String.format(
                        "Invoice paidAmount [%s] cannot exceed totalAmount [%s]", paidAmount, totalAmount));
            }
        }

        if (paymentStatus == null) {
            paymentStatus = InvoicePaymentStatus.ISSUED;
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isFullyPaid() {
        return paidAmount != null && paidAmount.compareTo(totalAmount) == 0;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isPartiallyPaid() {
        return paidAmount != null && paidAmount.isPositive() && paidAmount.isLessThan(totalAmount);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String invoiceId = UUID.randomUUID().toString();
        private FinancialParty issuer;
        private FinancialParty customer;
        private String invoiceNumber;
        private LocalDate issueDate = LocalDate.now();
        private LocalDate dueDate = LocalDate.now().plusDays(30);
        private Money subtotal;
        private Money taxAmount;
        private Money totalAmount;
        private String currency;
        private InvoicePaymentStatus paymentStatus = InvoicePaymentStatus.ISSUED;
        private Money paidAmount;

        public Builder invoiceId(String invoiceId) { this.invoiceId = invoiceId; return this; }
        public Builder issuer(FinancialParty issuer) { this.issuer = issuer; return this; }
        public Builder customer(FinancialParty customer) { this.customer = customer; return this; }
        public Builder invoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; return this; }
        public Builder issueDate(LocalDate issueDate) { this.issueDate = issueDate; return this; }
        public Builder dueDate(LocalDate dueDate) { this.dueDate = dueDate; return this; }
        public Builder subtotal(Money subtotal) {
            this.subtotal = subtotal;
            if (this.currency == null && subtotal != null) this.currency = subtotal.currency();
            return this;
        }
        public Builder taxAmount(Money taxAmount) { this.taxAmount = taxAmount; return this; }
        public Builder totalAmount(Money totalAmount) {
            this.totalAmount = totalAmount;
            if (this.currency == null && totalAmount != null) this.currency = totalAmount.currency();
            return this;
        }
        public Builder currency(String currency) { this.currency = currency; return this; }
        public Builder paymentStatus(InvoicePaymentStatus paymentStatus) { this.paymentStatus = paymentStatus; return this; }
        public Builder paidAmount(Money paidAmount) { this.paidAmount = paidAmount; return this; }

        public Invoice build() {
            if (totalAmount == null && subtotal != null) {
                totalAmount = taxAmount != null ? subtotal.add(taxAmount) : subtotal;
            }
            return new Invoice(invoiceId, issuer, customer, invoiceNumber, issueDate, dueDate, subtotal, taxAmount, totalAmount, currency, paymentStatus, paidAmount);
        }
    }
}
