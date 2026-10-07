package com.example.app.payment;

import java.util.UUID;

/**
 * Stand-in for a real card terminal. No network calls, no card data is stored.
 * Card numbers ending in 0002 are declined, so the decline path can be exercised.
 * Replace this class with a real gateway client when one is needed.
 */
public final class SimulatedCardProvider implements PaymentProvider {

    static final String DECLINE_SUFFIX = "0002";

    @Override
    public String authorize(long amountCents, String reference) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("Card amount must be positive");
        }
        String digits = reference == null ? "" : reference.replaceAll("\\D", "");
        if (digits.length() < 12) {
            throw new PaymentDeclinedException("Card number is missing or too short");
        }
        if (digits.endsWith(DECLINE_SUFFIX)) {
            throw new PaymentDeclinedException("Card declined by issuer (simulated)");
        }
        return "SIM-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
