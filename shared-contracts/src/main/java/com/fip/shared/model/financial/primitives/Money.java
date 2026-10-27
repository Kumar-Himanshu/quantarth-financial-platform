package com.fip.shared.model.financial.primitives;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fip.shared.model.financial.validation.FinancialValidationException;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable canonical Money value object.
 * Guarantees arbitrary precision, ISO 4217 currency compliance, explicit Banker's Rounding,
 * currency-specific precision handling, and zero IEEE 754 floating-point contamination.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class Money implements Comparable<Money>, Serializable {

    private static final long serialVersionUID = 1L;
    private static final Pattern CURRENCY_CODE_PATTERN = Pattern.compile("^[A-Z]{3}$");
    public static final int MAX_SCALE = 6;
    public static final RoundingMode DEFAULT_ROUNDING_MODE = RoundingMode.HALF_EVEN;

    private final BigDecimal amount;
    private final String currency;

    @JsonCreator
    public Money(
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("currency") String currency) {
        if (amount == null) {
            throw new FinancialValidationException("Money amount cannot be null");
        }
        if (currency == null || currency.isBlank()) {
            throw new FinancialValidationException("Money currency cannot be null or blank");
        }
        String normalizedCurrency = validateCurrencyCode(currency);

        // Normalize negative scales (e.g. 1E2 -> 100 with scale 0)
        BigDecimal normalizedAmount = amount.scale() < 0 ? amount.setScale(0, DEFAULT_ROUNDING_MODE) : amount;

        if (normalizedAmount.scale() > MAX_SCALE) {
            throw new FinancialValidationException(String.format(
                    "Monetary precision scale [%d] exceeds maximum permissible scale [%d]",
                    normalizedAmount.scale(), MAX_SCALE));
        }

        this.amount = normalizedAmount;
        this.currency = normalizedCurrency;
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    public static Money of(String amount, String currency) {
        if (amount == null || amount.isBlank()) {
            throw new FinancialValidationException("Money amount string cannot be null or blank");
        }
        try {
            return new Money(new BigDecimal(amount.trim()), currency);
        } catch (NumberFormatException e) {
            throw new FinancialValidationException("Invalid numeric string representation for Money: " + amount, e);
        }
    }

    public static Money of(long amount, String currency) {
        return new Money(BigDecimal.valueOf(amount), currency);
    }

    public static Money inr(BigDecimal amount) {
        return new Money(amount, "INR");
    }

    public static Money inr(String amount) {
        return of(amount, "INR");
    }

    public static Money inr(long amount) {
        return of(amount, "INR");
    }

    public static Money usd(BigDecimal amount) {
        return new Money(amount, "USD");
    }

    public static Money usd(String amount) {
        return of(amount, "USD");
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public BigDecimal amount() {
        return amount;
    }

    public String currency() {
        return currency;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    @JsonIgnore
    public int getCurrencyFractionDigits() {
        return getCurrencyFractionDigits(this.currency);
    }

    public static String validateCurrencyCode(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new FinancialValidationException("currency cannot be null or blank");
        }
        String normalizedCurrency = currency.trim().toUpperCase();
        if (!CURRENCY_CODE_PATTERN.matcher(normalizedCurrency).matches()) {
            throw new FinancialValidationException("Invalid currency code format: " + currency + ". Must be 3 uppercase letters");
        }
        try {
            Currency.getInstance(normalizedCurrency);
        } catch (IllegalArgumentException e) {
            throw new FinancialValidationException("Unsupported ISO 4217 currency code: " + normalizedCurrency);
        }
        return normalizedCurrency;
    }

    public static int getCurrencyFractionDigits(String currency) {
        validateCurrencyCode(currency);
        return Math.max(0, Currency.getInstance(currency.toUpperCase()).getDefaultFractionDigits());
    }

    public Money toCurrencyPrecision() {
        return toCurrencyPrecision(DEFAULT_ROUNDING_MODE);
    }

    public Money toCurrencyPrecision(RoundingMode roundingMode) {
        int targetDigits = getCurrencyFractionDigits();
        return withScale(targetDigits, roundingMode != null ? roundingMode : DEFAULT_ROUNDING_MODE);
    }

    public Money add(Money other) {
        validateSameCurrency(other);
        return new Money(this.amount.add(other.amount), this.currency);
    }

    public Money subtract(Money other) {
        validateSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), this.currency);
    }

    public Money multiply(BigDecimal multiplier) {
        return multiply(multiplier, DEFAULT_ROUNDING_MODE);
    }

    public Money multiply(BigDecimal multiplier, RoundingMode roundingMode) {
        if (multiplier == null) {
            throw new FinancialValidationException("Multiplier cannot be null");
        }
        BigDecimal result = this.amount.multiply(multiplier);
        if (result.scale() > MAX_SCALE) {
            result = result.setScale(MAX_SCALE, roundingMode != null ? roundingMode : DEFAULT_ROUNDING_MODE);
        }
        return new Money(result, this.currency);
    }

    public Money multiply(long multiplier) {
        return multiply(BigDecimal.valueOf(multiplier));
    }

    public Money divide(BigDecimal divisor) {
        return divide(divisor, DEFAULT_ROUNDING_MODE);
    }

    public Money divide(BigDecimal divisor, RoundingMode roundingMode) {
        int currencyScale = getCurrencyFractionDigits();
        int targetScale = Math.min(MAX_SCALE, Math.max(this.amount.scale(), currencyScale));
        return divide(divisor, targetScale, roundingMode);
    }

    public Money divide(BigDecimal divisor, int targetScale, RoundingMode roundingMode) {
        if (divisor == null) {
            throw new FinancialValidationException("Divisor cannot be null");
        }
        if (divisor.compareTo(BigDecimal.ZERO) == 0) {
            throw new ArithmeticException("Division by zero in monetary calculation");
        }
        if (targetScale < 0 || targetScale > MAX_SCALE) {
            throw new FinancialValidationException(String.format(
                    "Target scale [%d] must be between 0 and maximum permissible scale [%d]",
                    targetScale, MAX_SCALE));
        }
        BigDecimal result = this.amount.divide(divisor, targetScale, roundingMode != null ? roundingMode : DEFAULT_ROUNDING_MODE);
        return new Money(result, this.currency);
    }

    public Money divide(long divisor) {
        return divide(BigDecimal.valueOf(divisor));
    }

    public Money abs() {
        return new Money(this.amount.abs(), this.currency);
    }

    public Money negate() {
        return new Money(this.amount.negate(), this.currency);
    }

    public Money withScale(int newScale, RoundingMode roundingMode) {
        if (newScale < 0 || newScale > MAX_SCALE) {
            throw new FinancialValidationException("Scale must be between 0 and " + MAX_SCALE);
        }
        return new Money(this.amount.setScale(newScale, roundingMode != null ? roundingMode : DEFAULT_ROUNDING_MODE), this.currency);
    }

    /**
     * Allocates this Money amount into N equal parts without losing cents/paisa.
     * The remainder from division is distributed one minimal unit at a time to the first parts.
     */
    public List<Money> allocate(int targets) {
        if (targets <= 0) {
            throw new FinancialValidationException("Allocation targets must be strictly positive (> 0)");
        }
        int scale = this.amount.scale();
        BigDecimal unit = BigDecimal.ONE.movePointLeft(scale);
        BigDecimal divisor = BigDecimal.valueOf(targets);
        BigDecimal lowShare = this.amount.divide(divisor, scale, RoundingMode.DOWN);
        BigDecimal remainder = this.amount.subtract(lowShare.multiply(divisor));

        int remainderUnits = remainder.movePointRight(scale).intValueExact();
        List<Money> results = new ArrayList<>(targets);
        for (int i = 0; i < targets; i++) {
            BigDecimal share = (i < remainderUnits) ? lowShare.add(unit) : lowShare;
            results.add(new Money(share, this.currency));
        }
        return results;
    }

    /**
     * Allocates this Money amount according to proportional ratios without penny/cent loss.
     */
    public List<Money> allocate(List<Long> ratios) {
        if (ratios == null || ratios.isEmpty()) {
            throw new FinancialValidationException("Allocation ratios cannot be null or empty");
        }
        long totalRatio = 0;
        for (Long r : ratios) {
            if (r == null || r < 0) {
                throw new FinancialValidationException("Ratios must be non-negative");
            }
            totalRatio += r;
        }
        if (totalRatio == 0) {
            throw new FinancialValidationException("Sum of ratios must be strictly positive");
        }
        int scale = this.amount.scale();
        BigDecimal unit = BigDecimal.ONE.movePointLeft(scale);
        BigDecimal totalBD = BigDecimal.valueOf(totalRatio);
        List<Money> results = new ArrayList<>(ratios.size());
        BigDecimal allocatedSum = BigDecimal.ZERO;

        for (Long r : ratios) {
            BigDecimal share = this.amount.multiply(BigDecimal.valueOf(r)).divide(totalBD, scale, RoundingMode.DOWN);
            results.add(new Money(share, this.currency));
            allocatedSum = allocatedSum.add(share);
        }

        BigDecimal remainder = this.amount.subtract(allocatedSum);
        int remainderUnits = remainder.movePointRight(scale).intValueExact();
        for (int i = 0; i < remainderUnits; i++) {
            results.set(i, new Money(results.get(i).amount().add(unit), this.currency));
        }
        return results;
    }

    @JsonIgnore
    public boolean isPositive() {
        return this.amount.compareTo(BigDecimal.ZERO) > 0;
    }

    @JsonIgnore
    public boolean isNegative() {
        return this.amount.compareTo(BigDecimal.ZERO) < 0;
    }

    @JsonIgnore
    public boolean isZero() {
        return this.amount.compareTo(BigDecimal.ZERO) == 0;
    }

    @JsonIgnore
    public boolean isNonNegative() {
        return this.amount.compareTo(BigDecimal.ZERO) >= 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isGreaterThanOrEqual(Money other) {
        return compareTo(other) >= 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    public boolean isLessThanOrEqual(Money other) {
        return compareTo(other) <= 0;
    }

    public void validateSameCurrency(Money other) {
        if (other == null) {
            throw new FinancialValidationException("Comparison/operation operand cannot be null");
        }
        if (!this.currency.equalsIgnoreCase(other.currency)) {
            throw new CurrencyMismatchException(this.currency, other.currency);
        }
    }

    @Override
    public int compareTo(Money other) {
        validateSameCurrency(other);
        return this.amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Money money = (Money) o;
        return this.currency.equalsIgnoreCase(money.currency)
                && this.amount.compareTo(money.amount) == 0;
    }

    @Override
    public int hashCode() {
        // Normalizing zero and trailing zeros guarantees consistent hash for numerically equal values
        BigDecimal canonicalAmount = this.amount.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : this.amount.stripTrailingZeros();
        return Objects.hash(canonicalAmount, currency.toUpperCase());
    }

    @Override
    public String toString() {
        return currency + " " + amount.toPlainString();
    }
}
