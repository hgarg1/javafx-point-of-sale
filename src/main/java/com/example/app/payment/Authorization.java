package com.example.app.payment;

/** Proof that a provider took (or held) money. {@code reference} is what gets stored on the payment row. */
public record Authorization(String reference, long amountCents) {
}
