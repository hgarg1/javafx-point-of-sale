package com.example.app.repository;

import com.example.app.db.Columns;
import com.example.app.db.Database;
import com.example.app.domain.PaymentMethod;
import com.example.app.domain.Refund;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class RefundRepository {

    /** How much of one sale line has already been refunded. */
    public record RefundedSoFar(int qty, long amountCents) {
    }

    private final Database db;

    public RefundRepository(Database db) {
        this.db = db;
    }

    public long insertRefund(long saleId, long shiftId, long userId, long approvedBy, PaymentMethod method,
                             String reason, long amountCents, Instant createdAt) {
        return db.insert("INSERT INTO refunds (sale_id, shift_id, user_id, approved_by, method, reason, amount_cents, "
                        + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                saleId, shiftId, userId, approvedBy, method, reason, amountCents, createdAt);
    }

    public void insertLine(long refundId, long saleLineId, int qty, long amountCents) {
        db.update("INSERT INTO refund_lines (refund_id, sale_line_id, qty, amount_cents) VALUES (?, ?, ?, ?)",
                refundId, saleLineId, qty, amountCents);
    }

    public RefundedSoFar refundedSoFar(long saleLineId) {
        return db.queryOne("SELECT COALESCE(SUM(qty), 0), COALESCE(SUM(amount_cents), 0) "
                        + "FROM refund_lines WHERE sale_line_id = ?",
                rs -> new RefundedSoFar(rs.getInt(1), rs.getLong(2)), saleLineId).orElseThrow();
    }

    /** Refunded amount per payment method for all refunds in a shift. */
    public Map<PaymentMethod, Long> amountByMethodForShift(long shiftId) {
        Map<PaymentMethod, Long> totals = new EnumMap<>(PaymentMethod.class);
        db.query("SELECT method, SUM(amount_cents) FROM refunds WHERE shift_id = ? GROUP BY method", rs -> {
            totals.put(PaymentMethod.valueOf(rs.getString(1)), rs.getLong(2));
            return null;
        }, shiftId);
        return totals;
    }

    public List<Refund> findBetween(Instant fromInclusive, Instant toExclusive) {
        return db.query("SELECT id, sale_id, shift_id, user_id, approved_by, method, reason, amount_cents, created_at "
                + "FROM refunds WHERE created_at >= ? AND created_at < ? ORDER BY created_at, id",
                RefundRepository::map, fromInclusive, toExclusive);
    }

    private static Refund map(ResultSet rs) throws SQLException {
        return new Refund(
                rs.getLong("id"),
                rs.getLong("sale_id"),
                rs.getLong("shift_id"),
                rs.getLong("user_id"),
                rs.getLong("approved_by"),
                PaymentMethod.valueOf(rs.getString("method")),
                rs.getString("reason"),
                rs.getLong("amount_cents"),
                Columns.instant(rs, "created_at"));
    }
}
