package com.example.app.domain;

import com.example.app.error.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyFormatTest {

    @AfterEach
    void restoreDefaults() {
        MoneyFormat.configure("USD", "en-US");
    }

    @Test
    void dollarsAreShownWithSymbolAndThousandsSeparators() {
        assertEquals("$1,234.56", MoneyFormat.format(123_456));
        assertEquals("-$1.20", MoneyFormat.format(-120));
    }

    @Test
    void euroAmountsFollowTheGermanLayout() {
        MoneyFormat.configure("EUR", "de-DE");
        assertEquals("1.234,56 €", MoneyFormat.format(123_456).replace(" ", " "));
    }

    @Test
    void typedAmountsUseTheStoresDecimalSeparator() {
        MoneyFormat.configure("EUR", "de-DE");
        assertEquals(1250, MoneyFormat.parseCents("12,50"));
        assertEquals(123_456, MoneyFormat.parseCents("1.234,56 €"));
    }

    @Test
    void typedAmountsAcceptACurrencySymbolAndGrouping() {
        assertEquals(1250, MoneyFormat.parseCents("$12.50"));
        assertEquals(100_000, MoneyFormat.parseCents("1,000"));
    }

    @Test
    void currenciesThatAreNotTwoDecimalAreRefused() {
        BusinessException e = assertThrows(BusinessException.class, () -> MoneyFormat.configure("JPY", "en-US"));
        assertEquals("Currency JPY is not supported", e.getMessage());
    }

    @Test
    void unknownLocalesAreRefused() {
        assertThrows(BusinessException.class, () -> MoneyFormat.configure("USD", "xx-YY"));
    }
}
