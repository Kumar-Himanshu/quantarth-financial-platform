package com.fip.shared.model.financial.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fip.shared.model.financial.primitives.FinancialParty;
import com.fip.shared.model.financial.primitives.Money;
import com.fip.shared.model.financial.validation.FinancialValidationException;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Immutable Canonical Salary domain model.
 * Represents payroll disbursements and compensation credits.
 * Explicitly rejects automatic inference of gross salary from net salary.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Salary(
        String salaryId,
        FinancialParty employer,
        FinancialParty employee,
        Money grossAmount,
        Money netAmount,
        PayPeriod payPeriod,
        LocalDate paymentDate,
        List<SalaryDeduction> deductions
) implements Serializable {

    private static final long serialVersionUID = 1L;

    public Salary {
        if (salaryId == null || salaryId.isBlank()) {
            throw new FinancialValidationException("salaryId cannot be null or blank");
        }
        if (employer == null) {
            throw new FinancialValidationException("Salary employer cannot be null");
        }
        if (employee == null) {
            throw new FinancialValidationException("Salary employee cannot be null");
        }
        if (payPeriod == null) {
            throw new FinancialValidationException("Salary payPeriod cannot be null");
        }
        if (paymentDate == null) {
            throw new FinancialValidationException("Salary paymentDate cannot be null");
        }
        if (grossAmount == null && netAmount == null) {
            throw new FinancialValidationException("At least one of grossAmount or netAmount must be provided. Gross salary must never be automatically inferred from net salary.");
        }

        if (grossAmount != null && !grossAmount.isPositive()) {
            throw new FinancialValidationException("Gross salary amount must be positive, got: " + grossAmount);
        }
        if (netAmount != null && !netAmount.isPositive()) {
            throw new FinancialValidationException("Net salary amount must be positive, got: " + netAmount);
        }

        if (grossAmount != null && netAmount != null) {
            grossAmount.validateSameCurrency(netAmount);
            if (netAmount.isGreaterThan(grossAmount)) {
                throw new FinancialValidationException(String.format(
                        "Net salary [%s] cannot exceed gross salary [%s]", netAmount, grossAmount));
            }
        }

        if (deductions != null && !deductions.isEmpty()) {
            String expectedCurrency = grossAmount != null ? grossAmount.currency() : netAmount.currency();
            Money totalDeductions = Money.zero(expectedCurrency);
            for (SalaryDeduction d : deductions) {
                if (!d.amount().currency().equalsIgnoreCase(expectedCurrency)) {
                    throw new FinancialValidationException(String.format(
                            "Deduction currency [%s] does not match salary currency [%s]",
                            d.amount().currency(), expectedCurrency));
                }
                totalDeductions = totalDeductions.add(d.amount());
            }

            if (grossAmount != null && totalDeductions.isGreaterThan(grossAmount)) {
                throw new FinancialValidationException(String.format(
                        "Total deductions [%s] cannot exceed gross salary [%s]", totalDeductions, grossAmount));
            }
        }

        deductions = deductions != null ? List.copyOf(deductions) : Collections.emptyList();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String salaryId = UUID.randomUUID().toString();
        private FinancialParty employer;
        private FinancialParty employee;
        private Money grossAmount;
        private Money netAmount;
        private PayPeriod payPeriod;
        private LocalDate paymentDate = LocalDate.now();
        private List<SalaryDeduction> deductions = new ArrayList<>();

        public Builder salaryId(String salaryId) { this.salaryId = salaryId; return this; }
        public Builder employer(FinancialParty employer) { this.employer = employer; return this; }
        public Builder employee(FinancialParty employee) { this.employee = employee; return this; }
        public Builder grossAmount(Money grossAmount) { this.grossAmount = grossAmount; return this; }
        public Builder netAmount(Money netAmount) { this.netAmount = netAmount; return this; }
        public Builder payPeriod(PayPeriod payPeriod) { this.payPeriod = payPeriod; return this; }
        public Builder paymentDate(LocalDate paymentDate) { this.paymentDate = paymentDate; return this; }
        public Builder deductions(List<SalaryDeduction> deductions) {
            this.deductions = deductions != null ? new ArrayList<>(deductions) : new ArrayList<>();
            return this;
        }
        public Builder addDeduction(SalaryDeduction deduction) {
            if (deduction != null) this.deductions.add(deduction);
            return this;
        }

        public Salary build() {
            return new Salary(salaryId, employer, employee, grossAmount, netAmount, payPeriod, paymentDate, deductions);
        }
    }
}
