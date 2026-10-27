package com.fip.shared.model.financial.primitives;

public class CurrencyMismatchException extends IllegalArgumentException {

    private final String expectedCurrency;
    private final String actualCurrency;

    public CurrencyMismatchException(String message, String expectedCurrency, String actualCurrency) {
        super(message);
        this.expectedCurrency = expectedCurrency;
        this.actualCurrency = actualCurrency;
    }

    public CurrencyMismatchException(String expectedCurrency, String actualCurrency) {
        this(String.format("Currency mismatch: cannot operate between [%s] and [%s]", expectedCurrency, actualCurrency),
                expectedCurrency, actualCurrency);
    }

    public String getExpectedCurrency() {
        return expectedCurrency;
    }

    public String getActualCurrency() {
        return actualCurrency;
    }
}
