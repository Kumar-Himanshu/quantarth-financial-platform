package com.fip.shared.model.financial.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fip.shared.model.financial.primitives.AccountReference;
import com.fip.shared.model.financial.primitives.FinancialParty;
import com.fip.shared.model.financial.primitives.FinancialReference;
import com.fip.shared.model.financial.primitives.Money;
import com.fip.shared.model.financial.validation.FinancialValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalFinancialModelsTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("Payment: State machine permits valid transitions and rejects invalid/terminal transitions")
    void testPaymentStateTransitions() {
        Payment payment = Payment.builder()
                .paymentId("pay-upi-001")
                .amount(Money.inr(new BigDecimal("2500.00")))
                .payer(FinancialParty.individual("usr-1", "Rahul Verma"))
                .payee(FinancialParty.merchant("mer-10", "Swiggy India"))
                .paymentMethod(PaymentMethod.UPI)
                .status(PaymentStatus.INITIATED)
                .addReference(FinancialReference.upi("429800112233"))
                .build();

        assertThat(payment.status()).isEqualTo(PaymentStatus.INITIATED);

        // Transition: INITIATED -> PENDING
        Payment pending = payment.transitionTo(PaymentStatus.PENDING);
        assertThat(pending.status()).isEqualTo(PaymentStatus.PENDING);

        // Transition: PENDING -> COMPLETED (Terminal)
        Payment completed = pending.transitionTo(PaymentStatus.COMPLETED);
        assertThat(completed.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(completed.status().isTerminal()).isTrue();

        // Terminal state COMPLETED cannot transition to FAILED or CANCELLED
        assertThatThrownBy(() -> completed.transitionTo(PaymentStatus.FAILED))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("Illegal payment state transition");

        assertThatThrownBy(() -> completed.transitionTo(PaymentStatus.CANCELLED))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("Illegal payment state transition");
    }

    @Test
    @DisplayName("Payment: Rejects non-positive amounts and missing mandatory parties")
    void testPaymentValidationRules() {
        assertThatThrownBy(() -> Payment.builder()
                .amount(Money.inr(BigDecimal.ZERO))
                .payer(FinancialParty.individual("p1", "Alice"))
                .payee(FinancialParty.merchant("m1", "Store"))
                .paymentMethod(PaymentMethod.CARD)
                .build())
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("Payment amount must be strictly positive");

        assertThatThrownBy(() -> Payment.builder()
                .amount(Money.inr(new BigDecimal("-100.00")))
                .payer(FinancialParty.individual("p1", "Alice"))
                .payee(FinancialParty.merchant("m1", "Store"))
                .paymentMethod(PaymentMethod.CARD)
                .build())
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("Payment amount must be strictly positive");
    }

    @Test
    @DisplayName("Transfer: Validates distinct accounts and positive amounts")
    void testTransferValidation() {
        AccountReference accSavings = AccountReference.savings("acc-1", "HDFC", "XXXX1111", "HDFC0001");
        AccountReference accCurrent = AccountReference.current("acc-2", "ICICI", "XXXX2222", "ICIC0002");

        Transfer transfer = Transfer.builder()
                .transferId("trf-001")
                .sourceAccount(accSavings)
                .destinationAccount(accCurrent)
                .amount(Money.inr(new BigDecimal("100000.00")))
                .status(TransferStatus.COMPLETED)
                .build();

        assertThat(transfer.amount().amount()).isEqualTo(new BigDecimal("100000.00"));
        assertThat(transfer.status()).isEqualTo(TransferStatus.COMPLETED);

        // Identical source and destination account rejection
        assertThatThrownBy(() -> Transfer.builder()
                .sourceAccount(accSavings)
                .destinationAccount(accSavings)
                .amount(Money.inr(new BigDecimal("500.00")))
                .build())
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("Transfer sourceAccount and destinationAccount cannot be identical");
    }

    @Test
    @DisplayName("Salary: Disallows inferring gross salary from net salary and validates deductions")
    void testSalaryValidationRules() {
        FinancialParty employer = FinancialParty.organization("emp-infosys", "Infosys Limited");
        FinancialParty employee = FinancialParty.individual("emp-8821", "Sneha Rao");
        PayPeriod period = PayPeriod.ofMonth(2026, 10);

        // Case 1: Rejects when neither gross nor net is provided
        assertThatThrownBy(() -> Salary.builder()
                .employer(employer)
                .employee(employee)
                .payPeriod(period)
                .paymentDate(LocalDate.of(2026, 10, 31))
                .build())
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("Gross salary must never be automatically inferred from net salary");

        // Case 2: Rejects when net salary exceeds gross salary
        assertThatThrownBy(() -> Salary.builder()
                .employer(employer)
                .employee(employee)
                .payPeriod(period)
                .paymentDate(LocalDate.of(2026, 10, 31))
                .grossAmount(Money.inr(new BigDecimal("100000.00")))
                .netAmount(Money.inr(new BigDecimal("120000.00")))
                .build())
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("Net salary [INR 120000.00] cannot exceed gross salary [INR 100000.00]");

        // Case 3: Rejects when deductions exceed gross salary
        assertThatThrownBy(() -> Salary.builder()
                .employer(employer)
                .employee(employee)
                .payPeriod(period)
                .paymentDate(LocalDate.of(2026, 10, 31))
                .grossAmount(Money.inr(new BigDecimal("100000.00")))
                .netAmount(Money.inr(new BigDecimal("80000.00")))
                .addDeduction(SalaryDeduction.of("PROVIDENT_FUND", Money.inr(new BigDecimal("60000.00"))))
                .addDeduction(SalaryDeduction.of("TDS_INCOME_TAX", Money.inr(new BigDecimal("50000.00")))) // Total deductions 110,000 > 100,000
                .build())
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("Total deductions [INR 110000.00] cannot exceed gross salary");

        // Case 4: Valid Indian salary with PF and TDS
        Salary validSalary = Salary.builder()
                .salaryId("sal-2026-10-8821")
                .employer(employer)
                .employee(employee)
                .payPeriod(period)
                .paymentDate(LocalDate.of(2026, 10, 31))
                .grossAmount(Money.inr(new BigDecimal("150000.00")))
                .netAmount(Money.inr(new BigDecimal("125000.00")))
                .addDeduction(SalaryDeduction.of("EPF", Money.inr(new BigDecimal("10000.00")), "Employee Provident Fund"))
                .addDeduction(SalaryDeduction.of("TDS", Money.inr(new BigDecimal("15000.00")), "Income Tax Withholding"))
                .build();

        assertThat(validSalary.grossAmount().amount()).isEqualTo(new BigDecimal("150000.00"));
        assertThat(validSalary.deductions()).hasSize(2);
    }

    @Test
    @DisplayName("Refund: Supports partial refunds and rejects refund exceeding original payment")
    void testRefundValidation() {
        FinancialReference origPayRef = FinancialReference.paymentGateway("pay_998811", "RAZORPAY");
        Money originalAmount = Money.inr(new BigDecimal("5000.00"));

        // Valid Partial Refund
        Refund partialRefund = Refund.builder()
                .refundId("ref-001")
                .originalPaymentReference(origPayRef)
                .originalPaymentAmount(originalAmount)
                .refundAmount(Money.inr(new BigDecimal("1500.00")))
                .status(RefundStatus.COMPLETED)
                .reason("Customer returned partial shipment")
                .build();

        assertThat(partialRefund.isPartialRefund()).isTrue();
        assertThat(partialRefund.refundAmount().amount()).isEqualTo(new BigDecimal("1500.00"));

        // Reject refund amount greater than original payment
        assertThatThrownBy(() -> Refund.builder()
                .originalPaymentReference(origPayRef)
                .originalPaymentAmount(originalAmount)
                .refundAmount(Money.inr(new BigDecimal("5000.01")))
                .build())
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("Refund amount [INR 5000.01] cannot exceed original payment amount [INR 5000.00]");

        // Reject negative refund
        assertThatThrownBy(() -> Refund.builder()
                .originalPaymentReference(origPayRef)
                .refundAmount(Money.inr(new BigDecimal("-100.00")))
                .build())
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("refundAmount must be strictly positive");
    }

    @Test
    @DisplayName("Review 2: Cumulative refund validation and captured amount cap enforcement")
    void testCumulativeRefundValidation() {
        FinancialReference origPayRef = FinancialReference.paymentGateway("pay_998811", "RAZORPAY");
        Money capturedAmount = Money.inr(new BigDecimal("1000.00"));
        Money previousRefunds = Money.inr(new BigDecimal("700.00"));

        // Requesting 300 when 700 already refunded against 1000 captured is valid (exact ceiling)
        Refund validCumulative = Refund.builder()
                .refundId("ref-cum-1")
                .originalPaymentReference(origPayRef)
                .originalPaymentAmount(capturedAmount)
                .originalCapturedAmount(capturedAmount)
                .cumulativeRefundAmount(previousRefunds)
                .refundAmount(Money.inr(new BigDecimal("300.00")))
                .status(RefundStatus.INITIATED)
                .build();

        assertThat(validCumulative.isPartialRefund()).isTrue();
        assertThat(validCumulative.cumulativeRefundAmount()).isEqualTo(previousRefunds);

        // Requesting 300.01 when 700 already refunded against 1000 captured must be rejected
        assertThatThrownBy(() -> Refund.builder()
                .originalPaymentReference(origPayRef)
                .originalPaymentAmount(capturedAmount)
                .originalCapturedAmount(capturedAmount)
                .cumulativeRefundAmount(previousRefunds)
                .refundAmount(Money.inr(new BigDecimal("300.01")))
                .build())
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("Cumulative refunds [INR 1000.01] cannot exceed original captured payment amount [INR 1000.00]");
    }

    @Test
    @DisplayName("Review 2: Refund state machine transitions and terminal state enforcement")
    void testRefundStateTransitions() {
        FinancialReference origPayRef = FinancialReference.paymentGateway("pay_998811", "RAZORPAY");
        Refund refund = Refund.builder()
                .refundId("ref-state-1")
                .originalPaymentReference(origPayRef)
                .refundAmount(Money.inr(new BigDecimal("200.00")))
                .status(RefundStatus.INITIATED)
                .build();

        assertThat(refund.status()).isEqualTo(RefundStatus.INITIATED);
        assertThat(refund.canTransitionTo(RefundStatus.PENDING)).isTrue();

        Refund pending = refund.transitionTo(RefundStatus.PENDING);
        assertThat(pending.status()).isEqualTo(RefundStatus.PENDING);

        Refund completed = pending.transitionTo(RefundStatus.COMPLETED);
        assertThat(completed.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(completed.status().isTerminal()).isTrue();

        // Terminal state cannot transition to any other status
        assertThat(completed.canTransitionTo(RefundStatus.FAILED)).isFalse();
        assertThatThrownBy(() -> completed.transitionTo(RefundStatus.FAILED))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("Illegal refund state transition from COMPLETED to FAILED");
    }

    @Test
    @DisplayName("Loan: Validates principal, non-negative balance, and maturity date ordering")
    void testLoanValidation() {
        FinancialParty lender = FinancialParty.financialInstitution("hdfc-bank", "HDFC Bank Ltd");
        FinancialParty borrower = FinancialParty.individual("usr-50", "Vikram Malhotra");
        LocalDate disbursement = LocalDate.of(2026, 1, 1);
        LocalDate maturity = LocalDate.of(2046, 1, 1); // 20-year home loan

        Loan homeLoan = Loan.builder()
                .loanId("loan-hl-9901")
                .lender(lender)
                .borrower(borrower)
                .principalAmount(Money.inr(new BigDecimal("5000000.00"))) // 50 Lakhs INR
                .outstandingBalance(Money.inr(new BigDecimal("4850000.00")))
                .interestTerms(InterestTerms.floating(new BigDecimal("8.50"), "EBLR"))
                .disbursementDate(disbursement)
                .maturityDate(maturity)
                .status(LoanStatus.ACTIVE)
                .build();

        assertThat(homeLoan.principalAmount().amount()).isEqualTo(new BigDecimal("5000000.00"));
        assertThat(homeLoan.interestTerms().annualPercentageRate()).isEqualTo(new BigDecimal("8.50"));

        // Reject maturity before disbursement
        assertThatThrownBy(() -> Loan.builder()
                .lender(lender)
                .borrower(borrower)
                .principalAmount(Money.inr(new BigDecimal("100000.00")))
                .disbursementDate(LocalDate.of(2026, 5, 1))
                .maturityDate(LocalDate.of(2026, 4, 30))
                .build())
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("maturityDate [2026-04-30] cannot precede disbursementDate [2026-05-01]");
    }

    @Test
    @DisplayName("LoanRepayment: Supports non-EMI repayments and verifies component sums")
    void testLoanRepaymentValidation() {
        FinancialReference loanRef = FinancialReference.loanAccount("LAN-HDFC-9901", "HDFC_CORE");

        // Valid loan repayment (Principal + Interest + Fee <= Total Amount)
        LoanRepayment emi = LoanRepayment.builder()
                .repaymentId("rep-101")
                .loanReference(loanRef)
                .amount(Money.inr(new BigDecimal("43391.00")))
                .principalComponent(Money.inr(new BigDecimal("15000.00")))
                .interestComponent(Money.inr(new BigDecimal("28000.00")))
                .feeComponent(Money.inr(new BigDecimal("391.00")))
                .dueDate(LocalDate.of(2026, 11, 5))
                .paymentDate(LocalDate.of(2026, 11, 4))
                .status(RepaymentStatus.COMPLETED)
                .build();

        assertThat(emi.amount().amount()).isEqualTo(new BigDecimal("43391.00"));

        // Reject component sum exceeding total repayment amount
        assertThatThrownBy(() -> LoanRepayment.builder()
                .loanReference(loanRef)
                .amount(Money.inr(new BigDecimal("10000.00")))
                .principalComponent(Money.inr(new BigDecimal("8000.00")))
                .interestComponent(Money.inr(new BigDecimal("3000.00"))) // 8000 + 3000 = 11,000 > 10,000
                .dueDate(LocalDate.of(2026, 11, 5))
                .build())
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("exceeds total repayment amount");
    }

    @Test
    @DisplayName("Invoice: Subtotal + tax must match totalAmount, and paidAmount cannot exceed total")
    void testInvoiceConsistency() {
        FinancialParty issuer = FinancialParty.organization("org-cloud", "QuantArth Cloud Technologies Pvt Ltd");
        FinancialParty customer = FinancialParty.organization("cust-fin", "FinEdge Analytics");

        // Valid Invoice with 18% GST (9% CGST + 9% SGST = 1800 on 10000)
        Money subtotal = Money.inr(new BigDecimal("10000.00"));
        Money tax = Money.inr(new BigDecimal("1800.00"));
        Money total = Money.inr(new BigDecimal("11800.00"));

        Invoice invoice = Invoice.builder()
                .invoiceId("inv-2026-001")
                .issuer(issuer)
                .customer(customer)
                .invoiceNumber("QA-INV-2026-10-001")
                .issueDate(LocalDate.of(2026, 10, 1))
                .dueDate(LocalDate.of(2026, 10, 31))
                .subtotal(subtotal)
                .taxAmount(tax)
                .totalAmount(total)
                .paymentStatus(InvoicePaymentStatus.PARTIALLY_PAID)
                .paidAmount(Money.inr(new BigDecimal("5000.00")))
                .build();

        assertThat(invoice.isPartiallyPaid()).isTrue();
        assertThat(invoice.isFullyPaid()).isFalse();

        // Total amount inconsistency with tax
        assertThatThrownBy(() -> Invoice.builder()
                .issuer(issuer)
                .customer(customer)
                .invoiceNumber("QA-INV-ERR")
                .subtotal(subtotal)
                .taxAmount(tax)
                .totalAmount(Money.inr(new BigDecimal("11500.00"))) // Discrepancy: should be 11800
                .build())
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("does not equal subtotal");

        // Paid amount exceeding total amount
        assertThatThrownBy(() -> Invoice.builder()
                .issuer(issuer)
                .customer(customer)
                .invoiceNumber("QA-INV-OVERPAID")
                .subtotal(subtotal)
                .taxAmount(tax)
                .totalAmount(total)
                .paidAmount(Money.inr(new BigDecimal("12000.00")))
                .build())
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("paidAmount [INR 12000.00] cannot exceed totalAmount [INR 11800.00]");
    }

    @Test
    @DisplayName("Subscription: Validates recurring cadences and chronological dates")
    void testSubscriptionValidation() {
        FinancialParty provider = FinancialParty.organization("p-saas", "ArthQuant Intelligence Platform");
        FinancialParty subscriber = FinancialParty.organization("s-client", "Apex Capital Management");

        Subscription subscription = Subscription.builder()
                .subscriptionId("sub-apex-tier3")
                .provider(provider)
                .customer(subscriber)
                .recurringAmount(Money.usd(new BigDecimal("2500.00")))
                .billingFrequency(BillingFrequency.MONTHLY)
                .startDate(LocalDate.of(2026, 1, 1))
                .endDate(LocalDate.of(2026, 12, 31))
                .status(SubscriptionStatus.ACTIVE)
                .build();

        assertThat(subscription.billingFrequency()).isEqualTo(BillingFrequency.MONTHLY);
        assertThat(subscription.status()).isEqualTo(SubscriptionStatus.ACTIVE);

        // Reject endDate before startDate
        assertThatThrownBy(() -> Subscription.builder()
                .provider(provider)
                .customer(subscriber)
                .recurringAmount(Money.usd(new BigDecimal("100.00")))
                .billingFrequency(BillingFrequency.MONTHLY)
                .startDate(LocalDate.of(2026, 5, 1))
                .endDate(LocalDate.of(2026, 4, 30))
                .build())
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("endDate [2026-04-30] cannot precede startDate [2026-05-01]");
    }

    @Test
    @DisplayName("Tax: Supports direct/indirect taxes, India-specific GST/TDS, and negative tax refunds")
    void testTaxValidation() {
        // GST Tax liability scenario
        Tax gstTax = Tax.builder()
                .taxRecordId("tax-gst-2026-10")
                .taxType(TaxType.GST)
                .jurisdiction("IN-MAHARASHTRA")
                .taxPeriod("2026-Q3")
                .amount(Money.inr(new BigDecimal("350000.00")))
                .dueDate(LocalDate.of(2026, 11, 20))
                .paymentStatus(TaxPaymentStatus.DUE)
                .addReference(FinancialReference.gstin("27AABCT1234A1Z5"))
                .build();

        assertThat(gstTax.isRefund()).isFalse();
        assertThat(gstTax.taxType()).isEqualTo(TaxType.GST);

        // Tax Refund scenario (Negative amount allowed by finance sign conventions)
        Tax taxRefund = Tax.builder()
                .taxRecordId("tax-refund-ay2025")
                .taxType(TaxType.INCOME_TAX)
                .jurisdiction("IN-CENTRAL")
                .taxPeriod("AY-2025-26")
                .amount(Money.inr(new BigDecimal("-42500.00"))) // Negative = refund receivable
                .paymentStatus(TaxPaymentStatus.REFUND_DUE)
                .addReference(FinancialReference.taxCin("ITAX00192837465"))
                .build();

        assertThat(taxRefund.isRefund()).isTrue();
        assertThat(taxRefund.amount().isNegative()).isTrue();
    }
}
