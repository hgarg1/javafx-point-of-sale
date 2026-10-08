package com.example.app.repository;

import com.example.app.db.Columns;
import com.example.app.db.Database;
import com.example.app.domain.Refund;
import com.example.app.domain.RefundKind;
import com.example.app.domain.TenderTotal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
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

    /** Records a refund or void. {@code tender} is the code of the tender paid back to, {@code tenderName} its name then. */
    public long insertRefund(long saleId, long shiftId, long userId, long approvedBy, String tender, String tenderName,
                             String reason, long amountCents, Instant createdAt, String providerRef, RefundKind kind) {
        return db.insert("INSERT INTO refunds (sale_id, shift_id, user_id, approved_by, method, tender_name, reason, "
                        + "amount_cents, created_at, provider_ref, kind) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                saleId, shiftId, userId, approvedBy, tender, tenderName, reason, amountCents, createdAt, providerRef, kind);
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

    /** Refunded amount per tender code for one sale, refunds and voids together. Used to cap refunds at what each tender paid. */
    public Map<String, Long> refundedByTenderForSale(long saleId) {
        Map<String, Long> totals = new HashMap<>();
        db.query("SELECT method, SUM(amount_cents) FROM refunds WHERE sale_id = ? GROUP BY method", rs -> {
            totals.put(rs.getString(1), rs.getLong(2));
            return null;
        }, saleId);
        return totals;
    }

    /** Refunded or voided amount for all sales in a shift, grouped by tender and the name it had then. */
    public List<TenderTotal> amountByTenderForShift(long shiftId, RefundKind kind) {
        return db.query("SELECT method, tender_name, SUM(amount_cents) FROM refunds WHERE shift_id = ? AND kind = ? "
                        + "GROUP BY method, tender_name ORDER BY method, tender_name",
                rs -> new TenderTotal(rs.getString(1), rs.getString(2), rs.getLong(3)), shiftId, kind);
    }

    public List<Refund> findBetween(Instant fromInclusive, Instant toExclusive) {
        return db.query("SELECT id, sale_id, shift_id, user_id, approved_by, method, tender_name, reason, amount_cents, "
                + "created_at, kind FROM refunds WHERE created_at >= ? AND created_at < ? ORDER BY created_at, id",
                RefundRepository::map, fromInclusive, toExclusive);
    }

    private static Refund map(ResultSet rs) throws SQLException {
        return new Refund(
                rs.getLong("id"),
                rs.getLong("sale_id"),
                rs.getLong("shift_id"),
                rs.getLong("user_id"),
                rs.getLong("approved_by"),
                rs.getString("method"),
                rs.getString("tender_name"),
                rs.getString("reason"),
                rs.getLong("amount_cents"),
                Columns.instant(rs, "created_at"),
                RefundKind.valueOf(rs.getString("kind")));
    }
}
