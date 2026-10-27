package com.fip.shared.model.financial.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fip.shared.model.financial.validation.FinancialValidationException;

import java.io.Serializable;
import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record InterestTerms(
        BigDecimal annualPercentageRate,
        InterestType interestType,
        String benchmarkIndex,
        String compoundingFrequency
) implements Serializable {

    private static final long serialVersionUID = 1L;

    public InterestTerms {
        if (annualPercentageRate == null) {
            throw new FinancialValidationException("annualPercentageRate cannot be null");
        }
        if (annualPercentageRate.compareTo(BigDecimal.ZERO) < 0) {
            throw new FinancialValidationException("annualPercentageRate cannot be negative, got: " + annualPercentageRate);
        }
        if (interestType == null) {
            interestType = InterestType.FIXED;
        }
    }

    public static InterestTerms fixed(BigDecimal apr) {
        return new InterestTerms(apr, InterestType.FIXED, null, "MONTHLY");
    }

    public static InterestTerms floating(BigDecimal apr, String benchmark) {
        return new InterestTerms(apr, InterestType.FLOATING, benchmark, "MONTHLY");
    }
}
