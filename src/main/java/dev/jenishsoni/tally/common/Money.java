package dev.jenishsoni.tally.common;

import java.util.regex.Pattern;

/**
 * An amount in the smallest currency unit (cents) and its currency. Arithmetic is exact,
 * so overflow throws instead of wrapping around.
 */
public record Money(long minor, String currency) {

    private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");

    public Money {
        if (currency == null || !CURRENCY_CODE.matcher(currency).matches()) {
            throw new IllegalArgumentException("Currency must be 3 uppercase letters");
        }
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minor), currency);
    }

    public boolean isZero() {
        return minor == 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " and " + other.currency);
        }
    }

}
