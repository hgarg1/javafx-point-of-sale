package com.example.app.repository;

import com.example.app.db.Columns;
import com.example.app.db.Database;
import com.example.app.domain.Payment;
import com.example.app.domain.Sale;
import com.example.app.domain.SaleLine;
import com.example.app.domain.TenderTotal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
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

    public long count() {
        return db.queryOne("SELECT COUNT(*) FROM sales", rs -> rs.getLong(1)).orElse(0L);
    }

    public long nextReceiptSequence() {
        return db.queryOne("SELECT NEXT VALUE FOR receipt_seq", rs -> rs.getLong(1)).orElseThrow();
    }

    public long insertSale(String receiptNo, long shiftId, long userId, Long discountApprovedBy,
                           long subtotal, long discount, long tax, long total, Instant createdAt,
                           String idempotencyKey) {
        return db.insert("INSERT INTO sales (receipt_no, shift_id, user_id, discount_approved_by, subtotal_cents, "
                        + "discount_cents, tax_cents, total_cents, created_at, idempotency_key) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                receiptNo, shiftId, userId, discountApprovedBy, subtotal, discount, tax, total, createdAt,
                idempotencyKey);
    }

    public long insertLine(long saleId, long productId, String nameSnapshot, long unitPrice, int qty,
                           int discountBp, long discountCents, long taxCents, long lineTotal) {
        return db.insert("INSERT INTO sale_lines (sale_id, product_id, name_snapshot, unit_price_cents, qty, "
                        + "discount_bp, discount_cents, tax_cents, line_total_cents) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                saleId, productId, nameSnapshot, unitPrice, qty, discountBp, discountCents, taxCents, lineTotal);
    }

    /** Records a payment. {@code tender} is the tender's code and {@code tenderName} its name at this moment. */
    public long insertPayment(long saleId, String tender, String tenderName, long applied, long tendered, long change,
                              String providerRef) {
        return db.insert("INSERT INTO payments (sale_id, method, tender_name, applied_cents, tendered_cents, change_cents, "
                        + "provider_ref) VALUES (?, ?, ?, ?, ?, ?, ?)",
                saleId, tender, tenderName, applied, tendered, change, providerRef);
    }

    public Optional<Sale> findById(long id) {
        return db.queryOne(SALE_SELECT + " WHERE id = ?", SaleRepository::mapSale, id);
    }

    public Optional<Sale> findByReceiptNo(String receiptNo) {
        return db.queryOne(SALE_SELECT + " WHERE receipt_no = ?", SaleRepository::mapSale, receiptNo);
    }

    /** Lets a retried checkout (same key) return the sale it already created instead of charging twice. */
    public Optional<Sale> findByIdempotencyKey(String key) {
        return db.queryOne(SALE_SELECT + " WHERE idempotency_key = ?", SaleRepository::mapSale, key);
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
        return db.query("SELECT id, sale_id, method, tender_name, applied_cents, tendered_cents, change_cents, provider_ref "
                + "FROM payments WHERE sale_id = ? ORDER BY id", rs -> new Payment(
                rs.getLong("id"),
                rs.getLong("sale_id"),
                rs.getString("method"),
                rs.getString("tender_name"),
                rs.getLong("applied_cents"),
                rs.getLong("tendered_cents"),
                rs.getLong("change_cents"),
                rs.getString("provider_ref")), saleId);
    }

    /** Amount actually applied to the sale, grouped by tender and the name it had when paid. */
    public List<TenderTotal> appliedByTenderForSale(long saleId) {
        return db.query("SELECT method, tender_name, SUM(applied_cents) FROM payments WHERE sale_id = ? "
                        + "GROUP BY method, tender_name ORDER BY method, tender_name",
                rs -> new TenderTotal(rs.getString(1), rs.getString(2), rs.getLong(3)), saleId);
    }

    /** The provider reference of the first payment made with this tender, used to refund it. */
    public Optional<String> firstProviderRef(long saleId, String tender) {
        return db.queryOne("SELECT provider_ref FROM payments WHERE sale_id = ? AND method = ? ORDER BY id",
                rs -> rs.getString(1), saleId, tender);
    }

    /** Applied amount for all sales in a shift, grouped by tender and the name it had when paid. */
    public List<TenderTotal> appliedByTenderForShift(long shiftId) {
        return db.query("SELECT p.method, p.tender_name, SUM(p.applied_cents) FROM payments p JOIN sales s ON s.id = p.sale_id "
                        + "WHERE s.shift_id = ? GROUP BY p.method, p.tender_name ORDER BY p.method, p.tender_name",
                rs -> new TenderTotal(rs.getString(1), rs.getString(2), rs.getLong(3)), shiftId);
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
