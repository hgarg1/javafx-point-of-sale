package com.example.app.domain;

import java.time.Instant;

/** One append-only stock change. {@code qtyDelta} is positive for stock in, negative for stock out. */
public record StockMovement(
        long id,
        long productId,
        MovementType type,
        int qtyDelta,
        String reason,
        long userId,
        Long refSaleId,
        Instant createdAt) {
}
