package com.fip.shared.model.financial.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fip.shared.model.financial.primitives.Money;
import com.fip.shared.model.financial.validation.FinancialValidationException;

import java.io.Serializable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SalaryDeduction(
        String deductionType,
        Money amount,
        String description
) implements Serializable {

    private static final long serialVersionUID = 1L;

    public SalaryDeduction {
        if (deductionType == null || deductionType.isBlank()) {
            throw new FinancialValidationException("deductionType cannot be null or blank");
        }
        if (amount == null) {
            throw new FinancialValidationException("Deduction amount cannot be null");
        }
        if (!amount.isNonNegative()) {
            throw new FinancialValidationException("Deduction amount cannot be negative, got: " + amount);
        }
    }

    public static SalaryDeduction of(String deductionType, Money amount) {
        return new SalaryDeduction(deductionType, amount, null);
    }

    public static SalaryDeduction of(String deductionType, Money amount, String description) {
        return new SalaryDeduction(deductionType, amount, description);
    }
}
