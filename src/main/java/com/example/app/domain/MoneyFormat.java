package com.example.app.domain;

import com.example.app.error.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * How money is shown and typed in this store. Set once from the store settings and used everywhere through
 * {@link Money}. Only currencies with two decimal places are offered, because amounts are stored as cents.
 */
public final class MoneyFormat {

    /** Two-decimal currencies a store can trade in. */
    private static final Set<String> SUPPORTED = Set.of("USD", "EUR", "GBP", "CAD", "AUD", "NZD", "INR", "SGD",
            "HKD", "CHF", "SEK", "NOK", "DKK", "PLN", "ZAR", "BRL", "MXN");

    /** Locales offered for number and currency layout. */
    public static final List<String> LOCALES = List.of("en-US", "en-GB", "en-CA", "en-AU", "de-DE", "fr-FR", "es-ES", "nl-NL");

    private static volatile Currency currency = Currency.getInstance("USD");
    private static volatile Locale locale = Locale.US;

    private MoneyFormat() {
    }

    /** @throws BusinessException if the currency is not one the store can use, or the locale tag is not known */
    public static void configure(String currencyCode, String localeTag) {
        if (!SUPPORTED.contains(currencyCode)) {
            throw new BusinessException("Currency " + currencyCode + " is not supported");
        }
        if (!LOCALES.contains(localeTag)) {
            throw new BusinessException("Locale " + localeTag + " is not supported");
        }
        currency = Currency.getInstance(currencyCode);
        locale = Locale.forLanguageTag(localeTag);
    }

    public static List<String> supportedCurrencies() {
        return SUPPORTED.stream().sorted().toList();
    }

    public static String currencyCode() {
        return currency.getCurrencyCode();
    }

    public static String localeTag() {
        return locale.toLanguageTag();
    }

    /** "$1,234.56" for en-US; "1.234,56 €" for de-DE. Negative amounts keep their sign. */
    public static String format(long cents) {
        NumberFormat format = NumberFormat.getCurrencyInstance(locale);
        format.setCurrency(currency);
        return format.format(BigDecimal.valueOf(cents, 2));
    }

    /** The amount as someone would type it in this store's locale, e.g. "12,50" in de-DE. No symbol, no grouping. */
    public static String formatForInput(long cents) {
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setGroupingUsed(false);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(BigDecimal.valueOf(cents, 2));
    }

    /**
     * Reads what a person typed: a currency symbol, grouping and the decimal separator of the store's locale are all
     * accepted. Returns cents. Throws IllegalArgumentException if the text is not an amount.
     */
    public static long parseCents(String text) {
        String cleaned = text.replaceAll("[^0-9.,\\-]", "");
        if (cleaned.isEmpty() || cleaned.equals("-")) {
            throw new IllegalArgumentException("Not an amount: " + text);
        }
        DecimalFormat parser = (DecimalFormat) NumberFormat.getNumberInstance(locale);
        parser.setParseBigDecimal(true);
        try {
            BigDecimal value = (BigDecimal) parser.parse(cleaned);
            return value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (ParseException | ArithmeticException e) {
            throw new IllegalArgumentException("Not an amount: " + text, e);
        }
    }
}
