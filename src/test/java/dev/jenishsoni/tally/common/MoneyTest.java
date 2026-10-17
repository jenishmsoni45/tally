package dev.jenishsoni.tally.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    @Test
    void plusAddsAmountsOfTheSameCurrency() {
        Money sum = new Money(1_050, "CAD").plus(new Money(-300, "CAD"));

        assertThat(sum).isEqualTo(new Money(750, "CAD"));
    }

    @Test
    void plusThrowsOnOverflow() {
        Money max = new Money(Long.MAX_VALUE, "CAD");

        assertThatThrownBy(() -> max.plus(new Money(1, "CAD"))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void plusThrowsOnNegativeOverflow() {
        Money min = new Money(Long.MIN_VALUE, "CAD");

        assertThatThrownBy(() -> min.plus(new Money(-1, "CAD"))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void plusRejectsMismatchedCurrencies() {
        Money cad = new Money(100, "CAD");

        assertThatThrownBy(() -> cad.plus(new Money(100, "USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CAD")
                .hasMessageContaining("USD");
    }

    @Test
    void negateFlipsTheSign() {
        assertThat(new Money(250, "CAD").negate()).isEqualTo(new Money(-250, "CAD"));
        assertThat(new Money(-250, "CAD").negate()).isEqualTo(new Money(250, "CAD"));
    }

    @Test
    void negateThrowsForLongMinValue() {
        Money min = new Money(Long.MIN_VALUE, "CAD");

        assertThatThrownBy(min::negate).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void isZeroIsTrueOnlyForZero() {
        assertThat(new Money(0, "CAD").isZero()).isTrue();
        assertThat(new Money(1, "CAD").isZero()).isFalse();
        assertThat(new Money(-1, "CAD").isZero()).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "CA", "CADD", "cad", "Cad", "C4D", "CA ", "É€$"})
    void rejectsCurrencyThatIsNotThreeUppercaseLetters(String currency) {
        assertThatThrownBy(() -> new Money(1, currency)).isInstanceOf(IllegalArgumentException.class);
    }

}
