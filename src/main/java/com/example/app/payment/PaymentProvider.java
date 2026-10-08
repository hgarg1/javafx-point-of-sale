package com.example.app.payment;

/**
 * Moves money through one payment method.
 *
 * <p>Checkout follows a strict order: {@link #authorize} every tender, write the sale in one database
 * transaction, and if anything fails after authorization, {@link #voidAuthorization} each one. A declined
 * authorization must leave nothing behind.
 */
public interface PaymentProvider {

    /**
     * Takes the money, or places a hold for it.
     *
     * @param amountCents amount to charge, always positive
     * @param instrument  the card number for card payments; null for cash
     * @return the authorization to store on the payment record
     * @throws PaymentDeclinedException if the provider refuses the payment
     */
    Authorization authorize(long amountCents, String instrument);

    /** Releases an authorization whose sale was never committed. */
    void voidAuthorization(Authorization authorization);

    /**
     * Returns money to the customer against an earlier payment.
     *
     * @param originalReference the reference stored on the original payment
     * @return the provider's reference for the refund
     */
    String refund(String originalReference, long amountCents);
}
