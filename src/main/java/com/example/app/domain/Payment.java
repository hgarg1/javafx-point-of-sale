package com.example.app.domain;

public record Payment(
        long id,
        long saleId,
        PaymentMethod method,
        long appliedCents,
        long tenderedCents,
        long changeCents,
        String providerRef) {
}
