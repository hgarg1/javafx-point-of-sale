package com.example.app.payment;

/** Cash is always accepted and never held; the drawer is counted at shift close. */
public final class CashProvider implements PaymentProvider {

    static final String REFERENCE = "CASH";
    static final String REFUND_REFERENCE = "CASH-REFUND";

    @Override
    public Authorization authorize(long amountCents, String instrument) {
        requirePositive(amountCents);
        return new Authorization(REFERENCE, amountCents);
    }

    @Override
    public void voidAuthorization(Authorization authorization) {
        // Nothing is held for cash, so there is nothing to release.
    }

    @Override
    public String refund(String originalReference, long amountCents) {
        requirePositive(amountCents);
        return REFUND_REFERENCE;
    }

    private static void requirePositive(long amountCents) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("Cash amount must be positive");
        }
    }
}
