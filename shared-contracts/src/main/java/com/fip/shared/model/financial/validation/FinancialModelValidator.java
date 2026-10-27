package com.fip.shared.model.financial.validation;

import com.fip.shared.events.CanonicalEvent;
import com.fip.shared.model.financial.domain.Invoice;
import com.fip.shared.model.financial.domain.Loan;
import com.fip.shared.model.financial.domain.LoanRepayment;
import com.fip.shared.model.financial.domain.Payment;
import com.fip.shared.model.financial.domain.Refund;
import com.fip.shared.model.financial.domain.Salary;
import com.fip.shared.model.financial.domain.SalaryDeduction;
import com.fip.shared.model.financial.domain.Subscription;
import com.fip.shared.model.financial.domain.Tax;
import com.fip.shared.model.financial.domain.Transfer;
import com.fip.shared.model.financial.primitives.AccountReference;
import com.fip.shared.model.financial.primitives.FinancialParty;
import com.fip.shared.model.financial.primitives.FinancialReference;
import com.fip.shared.model.financial.primitives.FinancialTime;
import com.fip.shared.model.financial.primitives.Money;

import java.util.ArrayList;
import java.util.List;

/**
 * Centralized domain validator for the Canonical Financial Data Model.
 * Produces structured diagnostic messages across financial primitives, domain models,
 * and canonical event envelopes.
 */
public final class FinancialModelValidator {

    private FinancialModelValidator() {
        // Utility class
    }

    public static void validate(Money money) {
        if (money == null) {
            throw new FinancialValidationException("Money cannot be null");
        }
        // Invariants are enforced on construction; additional checks if needed
    }

    public static void validate(Payment payment) {
        if (payment == null) {
            throw new FinancialValidationException("Payment cannot be null");
        }
        List<String> errors = new ArrayList<>();
        if (payment.paymentId() == null || payment.paymentId().isBlank()) {
            errors.add("paymentId is required");
        }
        if (payment.amount() == null || !payment.amount().isPositive()) {
            errors.add("Payment amount must be strictly positive");
        }
        if (payment.payer() == null) {
            errors.add("Payer is required");
        }
        if (payment.payee() == null) {
            errors.add("Payee is required");
        }
        if (payment.paymentMethod() == null) {
            errors.add("Payment method is required");
        }
        if (payment.status() == null) {
            errors.add("Payment status is required");
        }
        if (!errors.isEmpty()) {
            throw new FinancialValidationException("Payment validation failed", errors);
        }
    }

    public static void validate(Transfer transfer) {
        if (transfer == null) {
            throw new FinancialValidationException("Transfer cannot be null");
        }
        List<String> errors = new ArrayList<>();
        if (transfer.transferId() == null || transfer.transferId().isBlank()) {
            errors.add("transferId is required");
        }
        if (transfer.sourceAccount() == null) {
            errors.add("sourceAccount is required");
        }
        if (transfer.destinationAccount() == null) {
            errors.add("destinationAccount is required");
        }
        if (transfer.sourceAccount() != null && transfer.destinationAccount() != null
                && transfer.sourceAccount().accountId().equalsIgnoreCase(transfer.destinationAccount().accountId())) {
            errors.add("Transfer source and destination accounts cannot be identical");
        }
        if (transfer.amount() == null || !transfer.amount().isPositive()) {
            errors.add("Transfer amount must be strictly positive");
        }
        if (!errors.isEmpty()) {
            throw new FinancialValidationException("Transfer validation failed", errors);
        }
    }

    public static void validate(Salary salary) {
        if (salary == null) {
            throw new FinancialValidationException("Salary cannot be null");
        }
        List<String> errors = new ArrayList<>();
        if (salary.salaryId() == null || salary.salaryId().isBlank()) {
            errors.add("salaryId is required");
        }
        if (salary.employer() == null) {
            errors.add("Employer party is required");
        }
        if (salary.employee() == null) {
            errors.add("Employee party is required");
        }
        if (salary.payPeriod() == null) {
            errors.add("payPeriod is required");
        }
        if (salary.paymentDate() == null) {
            errors.add("paymentDate is required");
        }
        if (salary.grossAmount() == null && salary.netAmount() == null) {
            errors.add("At least one of grossAmount or netAmount must be provided. Gross salary must not be inferred from net salary.");
        }
        if (salary.grossAmount() != null && salary.netAmount() != null) {
            if (!salary.grossAmount().currency().equalsIgnoreCase(salary.netAmount().currency())) {
                errors.add(String.format("Gross currency [%s] does not match net currency [%s]",
                        salary.grossAmount().currency(), salary.netAmount().currency()));
            } else if (salary.netAmount().isGreaterThan(salary.grossAmount())) {
                errors.add(String.format("Net salary [%s] exceeds gross salary [%s]",
                        salary.netAmount(), salary.grossAmount()));
            }
        }
        if (!errors.isEmpty()) {
            throw new FinancialValidationException("Salary validation failed", errors);
        }
    }

    public static void validate(Refund refund) {
        if (refund == null) {
            throw new FinancialValidationException("Refund cannot be null");
        }
        List<String> errors = new ArrayList<>();
        if (refund.refundId() == null || refund.refundId().isBlank()) {
            errors.add("refundId is required");
        }
        if (refund.originalPaymentReference() == null) {
            errors.add("originalPaymentReference is required");
        }
        if (refund.refundAmount() == null || !refund.refundAmount().isPositive()) {
            errors.add("Refund amount must be strictly positive");
        }
        if (refund.originalPaymentAmount() != null) {
            if (!refund.originalPaymentAmount().currency().equalsIgnoreCase(refund.refundAmount().currency())) {
                errors.add(String.format("Original payment currency [%s] does not match refund currency [%s]",
                        refund.originalPaymentAmount().currency(), refund.refundAmount().currency()));
            } else if (refund.refundAmount().isGreaterThan(refund.originalPaymentAmount())) {
                errors.add(String.format("Refund amount [%s] exceeds original payment amount [%s]",
                        refund.refundAmount(), refund.originalPaymentAmount()));
            }
        }
        if (!errors.isEmpty()) {
            throw new FinancialValidationException("Refund validation failed", errors);
        }
    }

    public static void validate(Loan loan) {
        if (loan == null) {
            throw new FinancialValidationException("Loan cannot be null");
        }
        List<String> errors = new ArrayList<>();
        if (loan.loanId() == null || loan.loanId().isBlank()) {
            errors.add("loanId is required");
        }
        if (loan.lender() == null) {
            errors.add("Lender party is required");
        }
        if (loan.borrower() == null) {
            errors.add("Borrower party is required");
        }
        if (loan.principalAmount() == null || !loan.principalAmount().isPositive()) {
            errors.add("Principal amount must be strictly positive");
        }
        if (loan.outstandingBalance() != null) {
            if (!loan.outstandingBalance().currency().equalsIgnoreCase(loan.principalAmount().currency())) {
                errors.add("Outstanding balance currency does not match principal currency");
            } else if (loan.outstandingBalance().isNegative()) {
                errors.add("Outstanding balance cannot be negative");
            }
        }
        if (loan.disbursementDate() == null) {
            errors.add("disbursementDate is required");
        }
        if (loan.maturityDate() != null && loan.disbursementDate() != null
                && loan.maturityDate().isBefore(loan.disbursementDate())) {
            errors.add(String.format("maturityDate [%s] cannot precede disbursementDate [%s]",
                    loan.maturityDate(), loan.disbursementDate()));
        }
        if (!errors.isEmpty()) {
            throw new FinancialValidationException("Loan validation failed", errors);
        }
    }

    public static void validate(LoanRepayment repayment) {
        if (repayment == null) {
            throw new FinancialValidationException("LoanRepayment cannot be null");
        }
        List<String> errors = new ArrayList<>();
        if (repayment.repaymentId() == null || repayment.repaymentId().isBlank()) {
            errors.add("repaymentId is required");
        }
        if (repayment.loanReference() == null) {
            errors.add("loanReference is required");
        }
        if (repayment.amount() == null || !repayment.amount().isPositive()) {
            errors.add("Repayment amount must be strictly positive");
        }
        if (repayment.dueDate() == null) {
            errors.add("dueDate is required");
        }
        if (!errors.isEmpty()) {
            throw new FinancialValidationException("LoanRepayment validation failed", errors);
        }
    }

    public static void validate(Invoice invoice) {
        if (invoice == null) {
            throw new FinancialValidationException("Invoice cannot be null");
        }
        List<String> errors = new ArrayList<>();
        if (invoice.invoiceId() == null || invoice.invoiceId().isBlank()) {
            errors.add("invoiceId is required");
        }
        if (invoice.issuer() == null) {
            errors.add("Issuer is required");
        }
        if (invoice.customer() == null) {
            errors.add("Customer is required");
        }
        if (invoice.invoiceNumber() == null || invoice.invoiceNumber().isBlank()) {
            errors.add("invoiceNumber is required");
        }
        if (invoice.issueDate() == null) {
            errors.add("issueDate is required");
        }
        if (invoice.dueDate() == null) {
            errors.add("dueDate is required");
        }
        if (invoice.dueDate() != null && invoice.issueDate() != null && invoice.dueDate().isBefore(invoice.issueDate())) {
            errors.add("dueDate cannot precede issueDate");
        }
        if (invoice.subtotal() == null || invoice.subtotal().isNegative()) {
            errors.add("Subtotal must be non-negative");
        }
        if (invoice.totalAmount() == null) {
            errors.add("totalAmount is required");
        }
        if (invoice.taxAmount() != null && invoice.subtotal() != null && invoice.totalAmount() != null) {
            Money expected = invoice.subtotal().add(invoice.taxAmount());
            if (!invoice.totalAmount().equals(expected)) {
                errors.add(String.format("totalAmount [%s] does not match subtotal [%s] + taxAmount [%s] (expected [%s])",
                        invoice.totalAmount(), invoice.subtotal(), invoice.taxAmount(), expected));
            }
        }
        if (invoice.paidAmount() != null && invoice.totalAmount() != null) {
            if (invoice.paidAmount().isNegative()) {
                errors.add("paidAmount cannot be negative");
            } else if (invoice.paidAmount().isGreaterThan(invoice.totalAmount())) {
                errors.add("paidAmount cannot exceed totalAmount");
            }
        }
        if (!errors.isEmpty()) {
            throw new FinancialValidationException("Invoice validation failed", errors);
        }
    }

    public static void validate(Subscription subscription) {
        if (subscription == null) {
            throw new FinancialValidationException("Subscription cannot be null");
        }
        List<String> errors = new ArrayList<>();
        if (subscription.subscriptionId() == null || subscription.subscriptionId().isBlank()) {
            errors.add("subscriptionId is required");
        }
        if (subscription.provider() == null) {
            errors.add("Provider party is required");
        }
        if (subscription.customer() == null) {
            errors.add("Customer party is required");
        }
        if (subscription.recurringAmount() == null || !subscription.recurringAmount().isPositive()) {
            errors.add("Recurring amount must be strictly positive");
        }
        if (subscription.billingFrequency() == null) {
            errors.add("billingFrequency is required");
        }
        if (subscription.startDate() == null) {
            errors.add("startDate is required");
        }
        if (subscription.endDate() != null && subscription.startDate() != null
                && subscription.endDate().isBefore(subscription.startDate())) {
            errors.add("endDate cannot precede startDate");
        }
        if (!errors.isEmpty()) {
            throw new FinancialValidationException("Subscription validation failed", errors);
        }
    }

    public static void validate(Tax tax) {
        if (tax == null) {
            throw new FinancialValidationException("Tax cannot be null");
        }
        List<String> errors = new ArrayList<>();
        if (tax.taxRecordId() == null || tax.taxRecordId().isBlank()) {
            errors.add("taxRecordId is required");
        }
        if (tax.taxType() == null) {
            errors.add("taxType is required");
        }
        if (tax.jurisdiction() == null || tax.jurisdiction().isBlank()) {
            errors.add("jurisdiction is required");
        }
        if (tax.taxPeriod() == null || tax.taxPeriod().isBlank()) {
            errors.add("taxPeriod is required");
        }
        if (tax.amount() == null) {
            errors.add("Tax amount cannot be null");
        }
        if (!errors.isEmpty()) {
            throw new FinancialValidationException("Tax validation failed", errors);
        }
    }

    public static void validate(CanonicalEvent event) {
        if (event == null) {
            throw new FinancialValidationException("CanonicalEvent cannot be null");
        }
        List<String> errors = new ArrayList<>();
        if (event.eventId() == null) {
            errors.add("eventId is required");
        }
        if (event.tenantId() == null || event.tenantId().isBlank()) {
            errors.add("tenantId is required");
        }
        if (event.eventType() == null || event.eventType().isBlank()) {
            errors.add("eventType is required");
        }
        if (event.schemaVersion() == null || event.schemaVersion().isBlank()) {
            errors.add("schemaVersion is required");
        }
        if (!errors.isEmpty()) {
            throw new FinancialValidationException("CanonicalEvent validation failed", errors);
        }
    }
}
