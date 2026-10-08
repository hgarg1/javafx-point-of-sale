package com.example.app.payment;

import com.example.app.domain.Money;

import java.util.UUID;

/**
 * Gift cards and store credit. Taking payment checks that the card has enough on it; the money itself moves in the
 * stored-value ledger, inside the sale's transaction, so this provider holds nothing and writes nothing. A refund
 * returns the money to the same card, which the refund service records.
 */
public final class StoredValueProvider implements PaymentProvider {

    private final StoredValueLookup cards;

    public StoredValueProvider(StoredValueLookup cards) {
        this.cards = cards;
    }

    @Override
    public Authorization authorize(long amountCents, String instrument) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        String code = instrument == null ? "" : instrument.replaceAll("\\s", "");
        if (code.isEmpty()) {
            throw new PaymentDeclinedException("Enter the gift card or store credit code");
        }
        if (!cards.exists(code)) {
            throw new PaymentDeclinedException("No gift card or store credit has that code");
        }
        long balance = cards.balance(code);
        if (balance < amountCents) {
            throw new PaymentDeclinedException("Only " + new Money(balance).format() + " is left on that card");
        }
        return new Authorization(code, amountCents);
    }

    @Override
    public void voidAuthorization(Authorization authorization) {
        // Nothing was written before the sale, so there is nothing to release.
    }

    @Override
    public String refund(String originalReference, long amountCents) {
        return "CREDIT-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
