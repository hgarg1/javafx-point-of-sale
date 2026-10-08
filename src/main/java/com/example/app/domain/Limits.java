package com.example.app.domain;

import com.example.app.error.BusinessException;

/** Upper bounds for money and quantities. They keep arithmetic far away from overflow and catch typos early. */
public final class Limits {

    /** $10,000,000.00 */
    public static final long MAX_AMOUNT_CENTS = 1_000_000_000L;

    public static final int MAX_QUANTITY = 100_000;

    private Limits() {
    }

    public static long checkAmount(long cents, String label) {
        if (cents < 0 || cents > MAX_AMOUNT_CENTS) {
            throw new BusinessException(label + " must be between $0.00 and "
                    + new Money(MAX_AMOUNT_CENTS).format());
        }
        return cents;
    }

    public static int checkQuantity(int quantity, String label) {
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw new BusinessException(label + " must be between 1 and " + MAX_QUANTITY);
        }
        return quantity;
    }
}
