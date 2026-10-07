package com.example.app.domain;

import java.time.Instant;

public record Sale(
        long id,
        String receiptNo,
        long shiftId,
        long userId,
        Long discountApprovedBy,
        long subtotalCents,
        long discountCents,
        long taxCents,
        long totalCents,
        Instant createdAt) {
}
