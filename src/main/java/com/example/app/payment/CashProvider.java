package com.example.app.payment;

/** Cash is always accepted; the drawer is counted at shift close. */
public final class CashProvider implements PaymentProvider {

    @Override
    public String authorize(long amountCents, String reference) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("Cash amount must be positive");
        }
        return "CASH";
    }
}
