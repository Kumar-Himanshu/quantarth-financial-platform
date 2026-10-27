package com.fip.shared.model.financial.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fip.shared.model.financial.primitives.FinancialParty;
import com.fip.shared.model.financial.primitives.Money;
import com.fip.shared.model.financial.validation.FinancialValidationException;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Immutable Canonical Loan domain model.
 * Represents a credit agreement, facility, or loan account state.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Loan(
        String loanId,
        FinancialParty lender,
        FinancialParty borrower,
        Money principalAmount,
        Money outstandingBalance,
        InterestTerms interestTerms,
        LocalDate disbursementDate,
        LocalDate maturityDate,
        LoanStatus status
) implements Serializable {

    private static final long serialVersionUID = 1L;

    public Loan {
        if (loanId == null || loanId.isBlank()) {
            throw new FinancialValidationException("loanId cannot be null or blank");
        }
        if (lender == null) {
            throw new FinancialValidationException("Loan lender cannot be null");
        }
        if (borrower == null) {
            throw new FinancialValidationException("Loan borrower cannot be null");
        }
        if (principalAmount == null) {
            throw new FinancialValidationException("Loan principalAmount cannot be null");
        }
        if (!principalAmount.isPositive()) {
            throw new FinancialValidationException("Loan principalAmount must be strictly positive (> 0), got: " + principalAmount);
        }

        if (outstandingBalance != null) {
            principalAmount.validateSameCurrency(outstandingBalance);
            if (outstandingBalance.isNegative()) {
                throw new FinancialValidationException("Loan outstandingBalance cannot be negative, got: " + outstandingBalance);
            }
        }

        if (disbursementDate == null) {
            throw new FinancialValidationException("Loan disbursementDate cannot be null");
        }

        if (maturityDate != null && maturityDate.isBefore(disbursementDate)) {
            throw new FinancialValidationException(String.format(
                    "Loan maturityDate [%s] cannot precede disbursementDate [%s]",
                    maturityDate, disbursementDate));
        }

        if (status == null) {
            throw new FinancialValidationException("Loan status cannot be null");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String loanId = UUID.randomUUID().toString();
        private FinancialParty lender;
        private FinancialParty borrower;
        private Money principalAmount;
        private Money outstandingBalance;
        private InterestTerms interestTerms;
        private LocalDate disbursementDate = LocalDate.now();
        private LocalDate maturityDate;
        private LoanStatus status = LoanStatus.APPLIED;

        public Builder loanId(String loanId) { this.loanId = loanId; return this; }
        public Builder lender(FinancialParty lender) { this.lender = lender; return this; }
        public Builder borrower(FinancialParty borrower) { this.borrower = borrower; return this; }
        public Builder principalAmount(Money principalAmount) { this.principalAmount = principalAmount; return this; }
        public Builder outstandingBalance(Money outstandingBalance) { this.outstandingBalance = outstandingBalance; return this; }
        public Builder interestTerms(InterestTerms interestTerms) { this.interestTerms = interestTerms; return this; }
        public Builder disbursementDate(LocalDate disbursementDate) { this.disbursementDate = disbursementDate; return this; }
        public Builder maturityDate(LocalDate maturityDate) { this.maturityDate = maturityDate; return this; }
        public Builder status(LoanStatus status) { this.status = status; return this; }

        public Loan build() {
            return new Loan(loanId, lender, borrower, principalAmount, outstandingBalance, interestTerms, disbursementDate, maturityDate, status);
        }
    }
}
