package com.example.app.domain;

/** Where a purchase order stands. It is worked out from the order's dates and deliveries, never stored. */
public enum PurchaseStatus {
    DRAFT,
    ORDERED,
    PARTIAL,
    RECEIVED,
    CANCELLED
}
