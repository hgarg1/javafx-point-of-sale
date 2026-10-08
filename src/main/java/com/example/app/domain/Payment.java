package com.example.app.domain;

/** One payment on a sale. {@code tenderName} is the tender's name as it was when the money was taken. */
public record Payment(
        long id,
        long saleId,
        String tender,
        String tenderName,
        long appliedCents,
        long tenderedCents,
        long changeCents,
        String providerRef) {
}
