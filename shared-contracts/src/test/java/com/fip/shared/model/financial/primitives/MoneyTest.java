package com.fip.shared.model.financial.primitives;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.shared.model.financial.validation.FinancialValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Money preserves arbitrary precision and rejects floating-point contamination")
    void testMoneyArbitraryPrecision() {
        BigDecimal highPrecision = new BigDecimal("1234567890123456.123456");
        Money money = Money.of(highPrecision, "INR");

        assertThat(money.amount()).isEqualTo(highPrecision);
        assertThat(money.currency()).isEqualTo("INR");
        assertThat(money.toString()).isEqualTo("INR 1234567890123456.123456");
    }

    @Test
    @DisplayName("Money rejects scale exceeding maximum permissible scale (6)")
    void testMoneyRejectsExcessiveScale() {
        BigDecimal excessiveScale = new BigDecimal("100.1234567"); // 7 decimals
        assertThatThrownBy(() -> Money.of(excessiveScale, "INR"))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("exceeds maximum permissible scale [6]");
    }

    @Test
    @DisplayName("Money validates ISO 4217 currency codes strictly")
    void testCurrencyValidation() {
        // Valid ISO codes
        assertThat(Money.of(new BigDecimal("100.00"), "INR").currency()).isEqualTo("INR");
        assertThat(Money.of(new BigDecimal("50.00"), "USD").currency()).isEqualTo("USD");
        assertThat(Money.of(new BigDecimal("75.00"), "EUR").currency()).isEqualTo("EUR");
        assertThat(Money.of(new BigDecimal("10.00"), "inr").currency()).isEqualTo("INR"); // Normalizes to uppercase

        // Invalid codes
        assertThatThrownBy(() -> Money.of(new BigDecimal("100.00"), "INVALID"))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("Invalid currency code format");

        assertThatThrownBy(() -> Money.of(new BigDecimal("100.00"), "XYZ"))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("Unsupported ISO 4217 currency code");

        assertThatThrownBy(() -> Money.of(new BigDecimal("100.00"), null))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("currency cannot be null or blank");

        assertThatThrownBy(() -> Money.of((BigDecimal) null, "INR"))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("amount cannot be null");
    }

    @Test
    @DisplayName("Cross-currency operations throw CurrencyMismatchException")
    void testCrossCurrencyRejection() {
        Money inr = Money.inr(new BigDecimal("1000.00"));
        Money usd = Money.usd(new BigDecimal("100.00"));

        assertThatThrownBy(() -> inr.add(usd))
                .isInstanceOf(CurrencyMismatchException.class)
                .hasMessageContaining("Currency mismatch");

        assertThatThrownBy(() -> inr.subtract(usd))
                .isInstanceOf(CurrencyMismatchException.class)
                .hasMessageContaining("Currency mismatch");

        assertThatThrownBy(() -> inr.compareTo(usd))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    @DisplayName("Arithmetic operations use explicit Banker's Rounding (HALF_EVEN)")
    void testArithmeticWithBankersRounding() {
        Money ten = Money.inr(new BigDecimal("10.00"));
        Money three = Money.inr(new BigDecimal("3.00"));

        // 10 / 3 = 3.333333 with max scale
        Money divided = ten.divide(new BigDecimal("3"));
        assertThat(divided.amount()).isEqualTo(new BigDecimal("3.33"));

        // Banker's Rounding test: 2.5 rounds to 2, 3.5 rounds to 4
        Money twoPointFive = Money.inr(new BigDecimal("2.5"));
        Money threePointFive = Money.inr(new BigDecimal("3.5"));

        assertThat(twoPointFive.withScale(0, RoundingMode.HALF_EVEN).amount()).isEqualTo(new BigDecimal("2"));
        assertThat(threePointFive.withScale(0, RoundingMode.HALF_EVEN).amount()).isEqualTo(new BigDecimal("4"));
    }

    @Test
    @DisplayName("Division by zero in Money calculation throws ArithmeticException")
    void testDivisionByZero() {
        Money money = Money.inr(new BigDecimal("100.00"));
        assertThatThrownBy(() -> money.divide(BigDecimal.ZERO))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("Division by zero");
    }

    @Test
    @DisplayName("Sign conventions: positive, negative, zero, and comparisons")
    void testSignConventionsAndComparisons() {
        Money positive = Money.inr(new BigDecimal("500.00"));
        Money negative = Money.inr(new BigDecimal("-200.00"));
        Money zero = Money.zero("INR");

        assertThat(positive.isPositive()).isTrue();
        assertThat(positive.isNegative()).isFalse();
        assertThat(positive.isZero()).isFalse();

        assertThat(negative.isNegative()).isTrue();
        assertThat(negative.isPositive()).isFalse();
        assertThat(negative.abs()).isEqualTo(Money.inr(new BigDecimal("200.00")));
        assertThat(negative.negate()).isEqualTo(Money.inr(new BigDecimal("200.00")));

        assertThat(zero.isZero()).isTrue();
        assertThat(zero.isNonNegative()).isTrue();

        assertThat(positive.isGreaterThan(zero)).isTrue();
        assertThat(negative.isLessThan(zero)).isTrue();
        assertThat(positive.isGreaterThanOrEqual(positive)).isTrue();
    }

    @Test
    @DisplayName("Equality evaluates numerical equality regardless of trailing zeros")
    void testEqualityWithDifferentScales() {
        Money m1 = Money.of(new BigDecimal("100.00"), "INR");
        Money m2 = Money.of(new BigDecimal("100.0"), "INR");
        Money m3 = Money.of(new BigDecimal("100"), "INR");
        Money different = Money.of(new BigDecimal("100.01"), "INR");

        assertThat(m1).isEqualTo(m2);
        assertThat(m2).isEqualTo(m3);
        assertThat(m1.hashCode()).isEqualTo(m2.hashCode());
        assertThat(m1).isNotEqualTo(different);
    }

    @Test
    @DisplayName("Money serializes and deserializes deterministically with Jackson")
    void testJacksonSerialization() throws Exception {
        Money original = Money.inr(new BigDecimal("9999.50"));
        String json = objectMapper.writeValueAsString(original);

        assertThat(json).contains("\"amount\":9999.50");
        assertThat(json).contains("\"currency\":\"INR\"");

        Money deserialized = objectMapper.readValue(json, Money.class);
        assertThat(deserialized).isEqualTo(original);
        assertThat(deserialized.currency()).isEqualTo("INR");
    }

    @Test
    @DisplayName("Review 1: No implicit rounding of incoming amounts")
    void testNoImplicitRoundingOfIncomingAmounts() {
        // High-precision fractional amount within max scale (6) is stored as-is without truncation
        BigDecimal unrounded = new BigDecimal("10.123456");
        Money money = Money.of(unrounded, "INR");
        assertThat(money.amount()).isEqualTo(unrounded);
        assertThat(money.amount().scale()).isEqualTo(6);
    }

    @Test
    @DisplayName("Review 1: Currency-specific precision handling for JPY (0), BHD (3), and INR/USD (2)")
    void testCurrencySpecificPrecisionHandling() {
        assertThat(Money.getCurrencyFractionDigits("JPY")).isEqualTo(0);
        assertThat(Money.getCurrencyFractionDigits("BHD")).isEqualTo(3);
        assertThat(Money.getCurrencyFractionDigits("INR")).isEqualTo(2);
        assertThat(Money.getCurrencyFractionDigits("USD")).isEqualTo(2);

        // JPY rounding to 0 decimals
        Money jpyRaw = Money.of(new BigDecimal("1000.75"), "JPY");
        Money jpyRounded = jpyRaw.toCurrencyPrecision(RoundingMode.HALF_UP);
        assertThat(jpyRounded.amount()).isEqualTo(new BigDecimal("1001"));
        assertThat(jpyRounded.amount().scale()).isEqualTo(0);

        // BHD rounding to 3 decimals
        Money bhdRaw = Money.of(new BigDecimal("5.12345"), "BHD");
        Money bhdRounded = bhdRaw.toCurrencyPrecision(RoundingMode.HALF_EVEN);
        assertThat(bhdRounded.amount()).isEqualTo(new BigDecimal("5.123"));
        assertThat(bhdRounded.amount().scale()).isEqualTo(3);
    }

    @Test
    @DisplayName("Review 1: Explicit rounding policies for division with scale and rounding mode")
    void testExplicitRoundingPoliciesForDivision() {
        Money amount = Money.usd(new BigDecimal("100.00"));
        
        // 100 / 3 with scale 4 and CEILING
        Money dividedCeil = amount.divide(new BigDecimal("3"), 4, RoundingMode.CEILING);
        assertThat(dividedCeil.amount()).isEqualTo(new BigDecimal("33.3334"));

        // 100 / 3 with scale 2 and FLOOR
        Money dividedFloor = amount.divide(new BigDecimal("3"), 2, RoundingMode.FLOOR);
        assertThat(dividedFloor.amount()).isEqualTo(new BigDecimal("33.33"));
    }

    @Test
    @DisplayName("Review 1: Pro-rata allocation distributes pennies exactly with zero remainder")
    void testProRataAllocationPreservesSum() {
        Money total = Money.inr(new BigDecimal("100.00"));
        // Allocate 100 in ratio 1 : 1 : 1
        List<Money> parts = total.allocate(List.of(1L, 1L, 1L));

        assertThat(parts).hasSize(3);
        // 33.34 + 33.33 + 33.33 = 100.00
        assertThat(parts.get(0).amount()).isEqualTo(new BigDecimal("33.34"));
        assertThat(parts.get(1).amount()).isEqualTo(new BigDecimal("33.33"));
        assertThat(parts.get(2).amount()).isEqualTo(new BigDecimal("33.33"));

        Money sum = parts.get(0).add(parts.get(1)).add(parts.get(2));
        assertThat(sum).isEqualTo(total);
    }

    @Test
    @DisplayName("Review 1: Scale-invariant equality and hashCode handles negative scale / scientific notation")
    void testEqualityAndHashCodeWithScientificNotation() {
        Money sci = Money.of(new BigDecimal("1E2"), "INR"); // 100 with scale -2
        Money std = Money.of(new BigDecimal("100.00"), "INR");
        Money plain = Money.of(new BigDecimal("100"), "INR");

        assertThat(sci).isEqualTo(std);
        assertThat(sci).isEqualTo(plain);
        assertThat(sci.hashCode()).isEqualTo(std.hashCode());
        assertThat(sci.hashCode()).isEqualTo(plain.hashCode());
    }
}
