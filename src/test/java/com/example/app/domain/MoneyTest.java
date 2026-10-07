package com.example.app.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {

    @Test
    void percentRoundsHalfUpToTheNearestCent() {
        assertEquals(80, Money.percentOf(1_000, 800));
        assertEquals(1, Money.percentOf(1, 5_000));
        assertEquals(0, Money.percentOf(1, 4_999));
    }

    @Test
    void proportionRoundsHalfUp() {
        assertEquals(67, Money.proportionOf(200, 1, 3));
        assertEquals(133, Money.proportionOf(200, 2, 3));
    }

    @Test
    void parsesCurrencyText() {
        assertEquals(1_250, Money.parseCents("$12.50"));
        assertEquals(1_250, Money.parseCents("12.5"));
        assertEquals(100_000, Money.parseCents("1,000"));
    }

    @Test
    void rejectsTextThatIsNotAnAmount() {
        assertThrows(IllegalArgumentException.class, () -> Money.parseCents("twelve"));
    }

    @Test
    void formatsPositiveAndNegativeAmounts() {
        assertEquals("$0.05", new Money(5).format());
        assertEquals("-$1.20", new Money(-120).format());
        assertEquals("12.00", Money.toPlainAmount(1_200));
    }
}
