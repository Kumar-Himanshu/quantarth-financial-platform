package com.fip.shared.model.financial.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fip.shared.model.financial.primitives.FinancialReference;
import com.fip.shared.model.financial.primitives.Money;
import com.fip.shared.model.financial.validation.FinancialValidationException;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Immutable Canonical LoanRepayment domain model.
 * Represents debt service disbursements, installment payments, or bullet payoffs.
 * Explicitly accommodates non-EMI repayments (interest-only, principal prepayment, fee settlements).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoanRepayment(
        String repaymentId,
        FinancialReference loanReference,
        Money amount,
        Money principalComponent,
        Money interestComponent,
        Money feeComponent,
        LocalDate dueDate,
        LocalDate paymentDate,
        RepaymentStatus status
) implements Serializable {

    private static final long serialVersionUID = 1L;

    public LoanRepayment {
        if (repaymentId == null || repaymentId.isBlank()) {
            throw new FinancialValidationException("repaymentId cannot be null or blank");
        }
        if (loanReference == null) {
            throw new FinancialValidationException("loanReference cannot be null");
        }
        if (amount == null) {
            throw new FinancialValidationException("Repayment amount cannot be null");
        }
        if (!amount.isPositive()) {
            throw new FinancialValidationException("Repayment amount must be strictly positive (> 0), got: " + amount);
        }
        if (dueDate == null) {
            throw new FinancialValidationException("dueDate cannot be null");
        }
        if (status == null) {
            throw new FinancialValidationException("Repayment status cannot be null");
        }

        Money componentsSum = Money.zero(amount.currency());
        boolean hasComponents = false;

        if (principalComponent != null) {
            amount.validateSameCurrency(principalComponent);
            if (!principalComponent.isNonNegative()) {
                throw new FinancialValidationException("principalComponent cannot be negative, got: " + principalComponent);
            }
            componentsSum = componentsSum.add(principalComponent);
            hasComponents = true;
        }

        if (interestComponent != null) {
            amount.validateSameCurrency(interestComponent);
            if (!interestComponent.isNonNegative()) {
                throw new FinancialValidationException("interestComponent cannot be negative, got: " + interestComponent);
            }
            componentsSum = componentsSum.add(interestComponent);
            hasComponents = true;
        }

        if (feeComponent != null) {
            amount.validateSameCurrency(feeComponent);
            if (!feeComponent.isNonNegative()) {
                throw new FinancialValidationException("feeComponent cannot be negative, got: " + feeComponent);
            }
            componentsSum = componentsSum.add(feeComponent);
            hasComponents = true;
        }

        if (hasComponents && componentsSum.isGreaterThan(amount)) {
            throw new FinancialValidationException(String.format(
                    "Sum of principal (%s), interest (%s), and fees (%s) [%s] exceeds total repayment amount [%s]",
                    principalComponent, interestComponent, feeComponent, componentsSum, amount));
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String repaymentId = UUID.randomUUID().toString();
        private FinancialReference loanReference;
        private Money amount;
        private Money principalComponent;
        private Money interestComponent;
        private Money feeComponent;
        private LocalDate dueDate = LocalDate.now();
        private LocalDate paymentDate;
        private RepaymentStatus status = RepaymentStatus.SCHEDULED;

        public Builder repaymentId(String repaymentId) { this.repaymentId = repaymentId; return this; }
        public Builder loanReference(FinancialReference loanReference) { this.loanReference = loanReference; return this; }
        public Builder amount(Money amount) { this.amount = amount; return this; }
        public Builder principalComponent(Money principalComponent) { this.principalComponent = principalComponent; return this; }
        public Builder interestComponent(Money interestComponent) { this.interestComponent = interestComponent; return this; }
        public Builder feeComponent(Money feeComponent) { this.feeComponent = feeComponent; return this; }
        public Builder dueDate(LocalDate dueDate) { this.dueDate = dueDate; return this; }
        public Builder paymentDate(LocalDate paymentDate) { this.paymentDate = paymentDate; return this; }
        public Builder status(RepaymentStatus status) { this.status = status; return this; }

        public LoanRepayment build() {
            return new LoanRepayment(repaymentId, loanReference, amount, principalComponent, interestComponent, feeComponent, dueDate, paymentDate, status);
        }
    }
}
