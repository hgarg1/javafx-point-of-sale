package com.example.app.repository;

import com.example.app.db.Columns;
import com.example.app.db.Database;
import com.example.app.domain.Payment;
import com.example.app.domain.PaymentMethod;
import com.example.app.domain.Sale;
import com.example.app.domain.SaleLine;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SaleRepository {

    private static final String SALE_SELECT = "SELECT id, receipt_no, shift_id, user_id, discount_approved_by, "
            + "subtotal_cents, discount_cents, tax_cents, total_cents, created_at FROM sales";

    private static final String LINE_SELECT = "SELECT id, sale_id, product_id, name_snapshot, unit_price_cents, qty, "
            + "discount_bp, discount_cents, tax_cents, line_total_cents FROM sale_lines";

    private final Database db;

    public SaleRepository(Database db) {
        this.db = db;
    }

    public long nextReceiptSequence() {
        return db.queryOne("SELECT NEXT VALUE FOR receipt_seq", rs -> rs.getLong(1)).orElseThrow();
    }

    public long insertSale(String receiptNo, long shiftId, long userId, Long discountApprovedBy,
                           long subtotal, long discount, long tax, long total, Instant createdAt) {
        return db.insert("INSERT INTO sales (receipt_no, shift_id, user_id, discount_approved_by, subtotal_cents, "
                        + "discount_cents, tax_cents, total_cents, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                receiptNo, shiftId, userId, discountApprovedBy, subtotal, discount, tax, total, createdAt);
    }

    public long insertLine(long saleId, long productId, String nameSnapshot, long unitPrice, int qty,
                           int discountBp, long discountCents, long taxCents, long lineTotal) {
        return db.insert("INSERT INTO sale_lines (sale_id, product_id, name_snapshot, unit_price_cents, qty, "
                        + "discount_bp, discount_cents, tax_cents, line_total_cents) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                saleId, productId, nameSnapshot, unitPrice, qty, discountBp, discountCents, taxCents, lineTotal);
    }

    public long insertPayment(long saleId, PaymentMethod method, long applied, long tendered, long change,
                              String providerRef) {
        return db.insert("INSERT INTO payments (sale_id, method, applied_cents, tendered_cents, change_cents, "
                        + "provider_ref) VALUES (?, ?, ?, ?, ?, ?)",
                saleId, method, applied, tendered, change, providerRef);
    }

    public Optional<Sale> findById(long id) {
        return db.queryOne(SALE_SELECT + " WHERE id = ?", SaleRepository::mapSale, id);
    }

    public Optional<Sale> findByReceiptNo(String receiptNo) {
        return db.queryOne(SALE_SELECT + " WHERE receipt_no = ?", SaleRepository::mapSale, receiptNo);
    }

    public List<Sale> findBetween(Instant fromInclusive, Instant toExclusive) {
        return db.query(SALE_SELECT + " WHERE created_at >= ? AND created_at < ? ORDER BY created_at, id",
                SaleRepository::mapSale, fromInclusive, toExclusive);
    }

    public Optional<SaleLine> findLine(long lineId) {
        return db.queryOne(LINE_SELECT + " WHERE id = ?", SaleRepository::mapLine, lineId);
    }

    public List<SaleLine> findLines(long saleId) {
        return db.query(LINE_SELECT + " WHERE sale_id = ? ORDER BY id", SaleRepository::mapLine, saleId);
    }

    public List<Payment> findPayments(long saleId) {
        return db.query("SELECT id, sale_id, method, applied_cents, tendered_cents, change_cents, provider_ref "
                + "FROM payments WHERE sale_id = ? ORDER BY id", rs -> new Payment(
                rs.getLong("id"),
                rs.getLong("sale_id"),
                PaymentMethod.valueOf(rs.getString("method")),
                rs.getLong("applied_cents"),
                rs.getLong("tendered_cents"),
                rs.getLong("change_cents"),
                rs.getString("provider_ref")), saleId);
    }

    /** Applied amount per payment method for all sales in a shift. */
    public Map<PaymentMethod, Long> appliedByMethodForShift(long shiftId) {
        Map<PaymentMethod, Long> totals = new EnumMap<>(PaymentMethod.class);
        db.query("SELECT p.method, SUM(p.applied_cents) FROM payments p JOIN sales s ON s.id = p.sale_id "
                + "WHERE s.shift_id = ? GROUP BY p.method", rs -> {
            totals.put(PaymentMethod.valueOf(rs.getString(1)), rs.getLong(2));
            return null;
        }, shiftId);
        return totals;
    }

    /** Count, total and tax for all sales in a shift. */
    public SalesTotals totalsForShift(long shiftId) {
        return db.queryOne("SELECT COUNT(*), COALESCE(SUM(total_cents), 0), COALESCE(SUM(tax_cents), 0) "
                        + "FROM sales WHERE shift_id = ?",
                rs -> new SalesTotals(rs.getLong(1), rs.getLong(2), rs.getLong(3)), shiftId).orElseThrow();
    }

    public record SalesTotals(long count, long totalCents, long taxCents) {
    }

    private static Sale mapSale(ResultSet rs) throws SQLException {
        return new Sale(
                rs.getLong("id"),
                rs.getString("receipt_no"),
                rs.getLong("shift_id"),
                rs.getLong("user_id"),
                Columns.longOrNull(rs, "discount_approved_by"),
                rs.getLong("subtotal_cents"),
                rs.getLong("discount_cents"),
                rs.getLong("tax_cents"),
                rs.getLong("total_cents"),
                Columns.instant(rs, "created_at"));
    }

    private static SaleLine mapLine(ResultSet rs) throws SQLException {
        return new SaleLine(
                rs.getLong("id"),
                rs.getLong("sale_id"),
                rs.getLong("product_id"),
                rs.getString("name_snapshot"),
                rs.getLong("unit_price_cents"),
                rs.getInt("qty"),
                rs.getInt("discount_bp"),
                rs.getLong("discount_cents"),
                rs.getLong("tax_cents"),
                rs.getLong("line_total_cents"));
    }
}
