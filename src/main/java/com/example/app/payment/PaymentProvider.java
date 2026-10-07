package com.example.app.payment;

/** Authorizes a payment. Implementations throw {@link PaymentDeclinedException} to refuse it. */
public interface PaymentProvider {

    /**
     * @param amountCents amount to charge, always positive
     * @param reference   payment-instrument reference from the cashier (card number for cards, null for cash)
     * @return a provider reference to store on the payment record
     */
    String authorize(long amountCents, String reference);
}
