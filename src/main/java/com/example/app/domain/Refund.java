package com.example.app.domain;

import java.time.Instant;

public record Refund(
        long id,
        long saleId,
        long shiftId,
        long userId,
        long approvedBy,
        String tender,
        String tenderName,
        String reason,
        long amountCents,
        Instant createdAt,
        RefundKind kind) {
}
