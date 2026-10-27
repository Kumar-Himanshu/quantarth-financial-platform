package com.fip.shared.model.financial.validation;

import com.fip.shared.events.CanonicalEvent;
import com.fip.shared.events.EntityType;
import com.fip.shared.model.financial.domain.BillingFrequency;
import com.fip.shared.model.financial.domain.Invoice;
import com.fip.shared.model.financial.domain.InvoicePaymentStatus;
import com.fip.shared.model.financial.domain.Loan;
import com.fip.shared.model.financial.domain.LoanRepayment;
import com.fip.shared.model.financial.domain.RepaymentStatus;
import com.fip.shared.model.financial.domain.LoanStatus;
import com.fip.shared.model.financial.domain.PayPeriod;
import com.fip.shared.model.financial.domain.Payment;
import com.fip.shared.model.financial.domain.PaymentMethod;
import com.fip.shared.model.financial.domain.PaymentStatus;
import com.fip.shared.model.financial.domain.Refund;
import com.fip.shared.model.financial.domain.RefundStatus;
import com.fip.shared.model.financial.domain.Salary;
import com.fip.shared.model.financial.domain.SalaryDeduction;
import com.fip.shared.model.financial.domain.Subscription;
import com.fip.shared.model.financial.domain.Tax;
import com.fip.shared.model.financial.domain.TaxPaymentStatus;
import com.fip.shared.model.financial.domain.TaxType;
import com.fip.shared.model.financial.domain.Transfer;
import com.fip.shared.model.financial.domain.TransferStatus;
import com.fip.shared.model.financial.primitives.AccountReference;
import com.fip.shared.model.financial.primitives.AccountType;
import com.fip.shared.model.financial.primitives.FinancialParty;
import com.fip.shared.model.financial.primitives.FinancialReference;
import com.fip.shared.model.financial.primitives.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinancialModelValidatorTest {

    @Test
    @DisplayName("Indian Scenario 1: Realistic NPCI UPI payment passes validator")
    void testIndianUpiPaymentScenario() {
        Payment upiPayment = Payment.builder()
                .paymentId("pay-upi-in-992182")
                .amount(Money.inr(new BigDecimal("1250.00")))
                .payer(FinancialParty.individual("usr-anil", "Anil Kumar", "XXXXXX9876K"))
                .payee(FinancialParty.merchant("mer-dmart", "Avenue Supermarts Ltd (DMart)"))
                .payerAccount(AccountReference.savings("acc-sbi-1", "SBIN", "XXXX5678", "SBIN0001234"))
                .payeeAccount(AccountReference.current("acc-hdfc-2", "HDFC", "XXXX9012", "HDFC0005678"))
                .paymentMethod(PaymentMethod.UPI)
                .status(PaymentStatus.COMPLETED)
                .addReference(FinancialReference.upi("429810987654"))
                .build();

        assertThatCode(() -> FinancialModelValidator.validate(upiPayment)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Indian Scenario 2: Indian Payroll with EPF and TDS passes validator")
    void testIndianPayrollScenario() {
        Salary salary = Salary.builder()
                .salaryId("sal-oct-2026-tcs-10928")
                .employer(FinancialParty.organization("tcs-corp", "Tata Consultancy Services Ltd", "XXXXXX1234T"))
                .employee(FinancialParty.individual("emp-tcs-10928", "Sunil Verma", "XXXXXX5678P"))
                .payPeriod(PayPeriod.ofMonth(2026, 10))
                .paymentDate(LocalDate.of(2026, 10, 31))
                .grossAmount(Money.inr(new BigDecimal("200000.00"))) // 2 Lakhs INR Gross
                .netAmount(Money.inr(new BigDecimal("162000.00")))   // 1.62 Lakhs INR Net
                .addDeduction(SalaryDeduction.of("EPF", Money.inr(new BigDecimal("15000.00")), "Employee Provident Fund (12%)"))
                .addDeduction(SalaryDeduction.of("TDS", Money.inr(new BigDecimal("21000.00")), "Tax Deducted at Source Sec 192"))
                .addDeduction(SalaryDeduction.of("PROFESSIONAL_TAX", Money.inr(new BigDecimal("200.00")), "Maharashtra Professional Tax"))
                .addDeduction(SalaryDeduction.of("HEALTH_INSURANCE", Money.inr(new BigDecimal("1800.00")), "Group Mediclaim Premium"))
                .build();

        assertThatCode(() -> FinancialModelValidator.validate(salary)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Indian Scenario 3: Bank Home Loan EMI repayment with HDFC Bank passes validator")
    void testIndianHomeLoanRepaymentScenario() {
        LoanRepayment emi = LoanRepayment.builder()
                .repaymentId("emi-oct-2026-hl-001")
                .loanReference(FinancialReference.loanAccount("HDFC-HL-449911", "HDFC_RETAIL_LOANS"))
                .amount(Money.inr(new BigDecimal("45680.00")))
                .principalComponent(Money.inr(new BigDecimal("16450.00")))
                .interestComponent(Money.inr(new BigDecimal("29230.00")))
                .dueDate(LocalDate.of(2026, 10, 10))
                .paymentDate(LocalDate.of(2026, 10, 8))
                .status(RepaymentStatus.COMPLETED)
                .build();

        assertThatCode(() -> FinancialModelValidator.validate(emi)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Indian Scenario 4: B2B Commercial Invoice with 18% GST (CGST+SGST) passes validator")
    void testIndianGstInvoiceScenario() {
        Invoice invoice = Invoice.builder()
                .invoiceId("inv-qa-2026-b2b-099")
                .issuer(FinancialParty.organization("org-qa", "QuantArth Platforms Private Limited", "XXXXXX9876Q"))
                .customer(FinancialParty.organization("org-hdfc-sec", "HDFC Securities Limited", "XXXXXX5432H"))
                .invoiceNumber("QA/2026-27/0045")
                .issueDate(LocalDate.of(2026, 10, 5))
                .dueDate(LocalDate.of(2026, 11, 4))
                .subtotal(Money.inr(new BigDecimal("500000.00"))) // 5 Lakhs Base
                .taxAmount(Money.inr(new BigDecimal("90000.00")))  // 18% GST = 90,000
                .totalAmount(Money.inr(new BigDecimal("590000.00")))
                .paymentStatus(InvoicePaymentStatus.ISSUED)
                .build();

        assertThatCode(() -> FinancialModelValidator.validate(invoice)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Indian Scenario 5: Advance Income Tax Challan 280 with CIN passes validator")
    void testIndianAdvanceTaxChallanScenario() {
        Tax advanceTax = Tax.builder()
                .taxRecordId("tax-challan-280-ay2027-q2")
                .taxType(TaxType.INCOME_TAX)
                .jurisdiction("IN-CENTRAL")
                .taxPeriod("AY-2027-28")
                .amount(Money.inr(new BigDecimal("125000.00")))
                .dueDate(LocalDate.of(2026, 9, 15))
                .paymentStatus(TaxPaymentStatus.PAID)
                .addReference(FinancialReference.taxCin("0002801509202612345"))
                .addReference(FinancialReference.gstin("27AABCT9988Z1Z2"))
                .build();

        assertThatCode(() -> FinancialModelValidator.validate(advanceTax)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Validator: Accumulates errors into rich FinancialValidationException on invalid inputs")
    void testValidatorErrorAccumulation() {
        assertThatThrownBy(() -> FinancialModelValidator.validate((Payment) null))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("Payment cannot be null");

        assertThatThrownBy(() -> FinancialModelValidator.validate((Transfer) null))
                .isInstanceOf(FinancialValidationException.class);

        assertThatThrownBy(() -> FinancialModelValidator.validate((Invoice) null))
                .isInstanceOf(FinancialValidationException.class);
    }
}
