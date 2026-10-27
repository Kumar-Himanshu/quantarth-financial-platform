package com.fip.shared.model.financial.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fip.shared.model.financial.validation.FinancialValidationException;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.YearMonth;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PayPeriod(
        LocalDate startDate,
        LocalDate endDate
) implements Serializable {

    private static final long serialVersionUID = 1L;

    public PayPeriod {
        if (startDate == null) {
            throw new FinancialValidationException("PayPeriod startDate cannot be null");
        }
        if (endDate == null) {
            throw new FinancialValidationException("PayPeriod endDate cannot be null");
        }
        if (endDate.isBefore(startDate)) {
            throw new FinancialValidationException(String.format(
                    "PayPeriod endDate [%s] cannot be before startDate [%s]", endDate, startDate));
        }
    }

    public static PayPeriod ofMonth(int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        return new PayPeriod(ym.atDay(1), ym.atEndOfMonth());
    }

    public static PayPeriod of(LocalDate start, LocalDate end) {
        return new PayPeriod(start, end);
    }
}
