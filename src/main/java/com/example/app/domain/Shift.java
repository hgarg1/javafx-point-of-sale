package com.example.app.domain;

import java.time.Instant;

public record Shift(
        long id,
        long userId,
        Instant openedAt,
        Instant closedAt,
        long openingFloatCents,
        Long countedCashCents,
        Long expectedCashCents) {

    public boolean isOpen() {
        return closedAt == null;
    }
}
