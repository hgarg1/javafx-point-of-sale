package com.example.app.payment;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stand-in for a card terminal. No network calls. The card number is used only to decide approval and is
 * never stored or returned: the sale records just the reference this class issues.
 *
 * <p>Card numbers ending in {@value #DECLINE_SUFFIX} are declined so the decline path can be exercised.
 * Holds are tracked so tests can check that a failed checkout leaves none behind.
 */
public final class SimulatedCardProvider implements PaymentProvider {

    static final String DECLINE_SUFFIX = "0002";

    /** Reference to amount, for charges that have not been voided. */
    private final Map<String, Long> charges = new HashMap<>();

    @Override
    public Authorization authorize(long amountCents, String instrument) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("Card amount must be positive");
        }
        String digits = instrument == null ? "" : instrument.replaceAll("\\D", "");
        if (digits.length() < 12) {
            throw new PaymentDeclinedException("Card number is missing or too short");
        }
        if (digits.endsWith(DECLINE_SUFFIX)) {
            throw new PaymentDeclinedException("Card declined by issuer (simulated)");
        }
        String reference = "SIM-" + UUID.randomUUID().toString().substring(0, 8);
        charges.put(reference, amountCents);
        return new Authorization(reference, amountCents);
    }

    @Override
    public void voidAuthorization(Authorization authorization) {
        charges.remove(authorization.reference());
    }

    @Override
    public String refund(String originalReference, long amountCents) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("Refund amount must be positive");
        }
        if (!charges.containsKey(originalReference)) {
            throw new IllegalArgumentException("No card payment with reference " + originalReference);
        }
        return "SIM-RFND-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Number of card charges that are still live (not voided). For tests. */
    public int liveCharges() {
        return charges.size();
    }
}
