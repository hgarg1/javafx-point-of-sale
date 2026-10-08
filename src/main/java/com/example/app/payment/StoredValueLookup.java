package com.example.app.payment;

/** What the stored-value provider needs to know about a gift card or store credit code. */
public interface StoredValueLookup {

    boolean exists(String code);

    long balance(String code);
}
