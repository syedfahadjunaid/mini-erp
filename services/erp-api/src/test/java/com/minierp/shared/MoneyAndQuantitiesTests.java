package com.minierp.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Pure arithmetic: exact conversions, one rounding rule, overflow is an error. */
class MoneyAndQuantitiesTests {

    @Test
    void convertsTypedQuantitiesExactlyIntoBaseUnits() {
        assertThat(Quantities.toBase(new BigDecimal("10"), 12, "q")).isEqualTo(120);      // 10 BOX of 12 EA
        assertThat(Quantities.toBase(new BigDecimal("2.5"), 12, "q")).isEqualTo(30);      // half boxes are fine...
        assertThat(Quantities.toBase(new BigDecimal("1.5"), 1000, "q")).isEqualTo(1500);  // 1.5 KG in G
        assertThat(Quantities.toBase(new BigDecimal("7.000"), 1, "q")).isEqualTo(7);
    }

    @Test
    void refusesQuantitiesThatAreNotWholeBaseUnits() {
        // ...but 0.5 EA can't exist, and rounding would silently invent or lose stock.
        assertThatThrownBy(() -> Quantities.toBase(new BigDecimal("0.5"), 1, "lines[0].quantity"))
                .isInstanceOf(Errors.BusinessRuleException.class)
                .hasMessageContaining("whole base units")
                .satisfies(e -> assertThat(((Errors.BusinessRuleException) e).problems().getFirst().field())
                        .isEqualTo("lines[0].quantity"));
        assertThatThrownBy(() -> Quantities.toBase(new BigDecimal("1.25"), 6, "q"))
                .isInstanceOf(Errors.BusinessRuleException.class);
        assertThatThrownBy(() -> Quantities.toBase(new BigDecimal("0.0001"), 1000, "q"))
                .hasMessageContaining("at most 3 decimals");
        assertThatThrownBy(() -> Quantities.toBase(BigDecimal.ZERO, 1, "q"))
                .hasMessageContaining("greater than zero");
    }

    @Test
    void roundsLineAmountsHalfUpToTheMinorUnitOnce() {
        assertThat(Money.lineAmount(new BigDecimal("1.5"), 399)).isEqualTo(599);   // 598.5 -> 599
        assertThat(Money.lineAmount(new BigDecimal("3"), 1250)).isEqualTo(3750);
        assertThat(Money.lineAmount(new BigDecimal("0.333"), 100)).isEqualTo(33);  // 33.3 -> 33
    }

    @Test
    void computesTaxInBasisPoints() {
        assertThat(Money.taxOn(10_000, 2000)).isEqualTo(2000);  // 20 % of 100.00
        assertThat(Money.taxOn(999, 2000)).isEqualTo(200);      // 199.8 -> 200
        assertThat(Money.taxOn(105, 550)).isEqualTo(6);         // 5.775 -> 6
        assertThat(Money.taxOn(1234, 0)).isZero();
    }

    @Test
    void formatsByTheCurrencysDecimals() {
        assertThat(Money.format(1250, "EUR")).isEqualTo("12.50");
        assertThat(Money.format(1250, "JPY")).isEqualTo("1250");
        assertThat(Money.format(-5, "EUR")).isEqualTo("-0.05");
    }

    @Test
    void overflowIsAnErrorNotAWrongNumber() {
        assertThatThrownBy(() -> Money.lineAmount(new BigDecimal("1000000000"), Long.MAX_VALUE / 2))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void validatesCurrencies() {
        assertThat(Money.requireCurrency("EUR", "currency")).isEqualTo("EUR");
        assertThatThrownBy(() -> Money.requireCurrency("EURO", "currency")).isInstanceOf(Errors.BusinessRuleException.class);
        assertThatThrownBy(() -> Money.requireCurrency("XYZ", "currency")).isInstanceOf(Errors.BusinessRuleException.class);
    }
}
