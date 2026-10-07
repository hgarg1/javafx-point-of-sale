package com.example.app.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    // Low iteration count keeps the test fast; the stored hash carries its own count.
    private final PasswordHasher hasher = new PasswordHasher(1_000);

    @Test
    void verifiesTheCorrectPasswordOnly() {
        String stored = hasher.hash("correct horse");
        assertTrue(hasher.verify("correct horse", stored));
        assertFalse(hasher.verify("wrong horse", stored));
    }

    @Test
    void saltsEachHashSoIdenticalPasswordsDiffer() {
        assertNotEquals(hasher.hash("same"), hasher.hash("same"));
    }

    @Test
    void storesIterationsInTheHash() {
        assertTrue(hasher.hash("x").startsWith("pbkdf2$1000$"));
    }

    @Test
    void rejectsMalformedStoredValues() {
        assertFalse(hasher.verify("x", "not-a-hash"));
    }
}
