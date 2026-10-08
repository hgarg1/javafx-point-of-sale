package com.example.app.payment;

import java.util.UUID;

/**
 * QR and wallet payments taken on another device. The till records the wallet's transaction reference; nothing is held
 * here. A refund to a wallet is made in the wallet's own app, so the till only records that it is owed.
 */
public final class ExternalPaymentProvider implements PaymentProvider {

    static final String REFUND_NOTE = "MANUAL-REFUND";

    @Override
    public Authorization authorize(long amountCents, String instrument) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        String reference = instrument == null ? "" : instrument.trim();
        if (reference.isEmpty()) {
            throw new PaymentDeclinedException("Enter the QR or wallet transaction reference");
        }
        if (reference.length() > 56) {
            throw new PaymentDeclinedException("That reference is too long");
        }
        return new Authorization("EXT:" + reference, amountCents);
    }

    @Override
    public void voidAuthorization(Authorization authorization) {
        // Nothing was taken from the customer's wallet until the sale is recorded, so nothing is released.
    }

    @Override
    public String refund(String originalReference, long amountCents) {
        return REFUND_NOTE + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
