package com.example.app.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * An amount of money in minor units (cents). Never use double for money.
 * Currency is fixed to USD for this version.
 */
public record Money(long cents) {

    public static final Money ZERO = new Money(0);

    public Money plus(Money other) {
        return new Money(Math.addExact(cents, other.cents));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(cents, other.cents));
    }

    /** Returns {@code cents * basisPoints / 10000}, rounded HALF_UP to the nearest cent. */
    public static long percentOf(long cents, int basisPoints) {
        return BigDecimal.valueOf(cents)
                .multiply(BigDecimal.valueOf(basisPoints))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** Returns {@code cents * part / whole}, rounded HALF_UP to the nearest cent. */
    public static long proportionOf(long cents, long part, long whole) {
        return BigDecimal.valueOf(cents)
                .multiply(BigDecimal.valueOf(part))
                .divide(BigDecimal.valueOf(whole), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** Reads an amount typed in the store's locale and currency. Throws IllegalArgumentException if invalid. */
    public static long parseCents(String text) {
        return MoneyFormat.parseCents(text);
    }

    /** The amount in the store's currency and locale, e.g. "$12.50". */
    public String format() {
        return MoneyFormat.format(cents);
    }

    /** Decimal with no currency symbol, e.g. "12.50". Used for CSV exports. */
    public static String toPlainAmount(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }
}
