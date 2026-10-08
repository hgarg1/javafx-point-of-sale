package com.example.app.domain;

/** A REFUND returns some items and money. A VOID reverses a whole sale while its shift is still open. */
public enum RefundKind {
    REFUND,
    VOID
}
