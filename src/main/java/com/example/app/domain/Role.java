package com.example.app.domain;

/** Ordered from least to most privileged; {@link #atLeast(Role)} relies on this order. */
public enum Role {
    CASHIER,
    MANAGER,
    ADMIN;

    public boolean atLeast(Role minimum) {
        return ordinal() >= minimum.ordinal();
    }
}
