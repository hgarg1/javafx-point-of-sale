package com.example.app.domain;

import java.time.Instant;

public record Refund(
        long id,
        long saleId,
        long shiftId,
        long userId,
        long approvedBy,
        PaymentMethod method,
        String reason,
        long amountCents,
        Instant createdAt) {
}
