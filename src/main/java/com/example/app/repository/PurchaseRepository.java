package com.example.app.repository;

import com.example.app.db.Columns;
import com.example.app.db.Database;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Purchase orders, their lines, and the goods receipts made against them. The service works out each order's status. */
public final class PurchaseRepository {

    /** An order header, with its supplier's name. */
    public record OrderRow(long id, long supplierId, String supplierName, String note, Instant createdAt,
                           Instant orderedAt, Instant cancelledAt) {
    }

    /** An order line, with how much of it has been received so far. */
    public record LineRow(long id, long productId, String sku, String name, int qtyOrdered, long unitCostCents,
                          int qtyReceived) {
    }

    private static final String ORDER_SELECT = "SELECT o.id, o.supplier_id, s.name AS supplier_name, o.note, "
            + "o.created_at, o.ordered_at, o.cancelled_at FROM purchase_orders o JOIN suppliers s ON s.id = o.supplier_id";

    private final Database db;

    public PurchaseRepository(Database db) {
        this.db = db;
    }

    public long insertOrder(long supplierId, String note, long userId, Instant createdAt) {
        return db.insert("INSERT INTO purchase_orders (supplier_id, note, created_by, created_at) VALUES (?, ?, ?, ?)",
                supplierId, note, userId, createdAt);
    }

    public long insertLine(long orderId, long productId, int qty, long unitCostCents) {
        return db.insert("INSERT INTO purchase_order_lines (purchase_order_id, product_id, qty_ordered, unit_cost_cents) "
                + "VALUES (?, ?, ?, ?)", orderId, productId, qty, unitCostCents);
    }

    public void deleteLine(long lineId) {
        db.update("DELETE FROM purchase_order_lines WHERE id = ?", lineId);
    }

    public void markOrdered(long orderId, Instant at) {
        db.update("UPDATE purchase_orders SET ordered_at = ? WHERE id = ?", at, orderId);
    }

    public void markCancelled(long orderId, Instant at) {
        db.update("UPDATE purchase_orders SET cancelled_at = ? WHERE id = ?", at, orderId);
    }

    public List<OrderRow> findOrders() {
        return db.query(ORDER_SELECT + " ORDER BY o.created_at DESC, o.id DESC", PurchaseRepository::mapOrder);
    }

    public Optional<OrderRow> findOrder(long id) {
        return db.queryOne(ORDER_SELECT + " WHERE o.id = ?", PurchaseRepository::mapOrder, id);
    }

    /** The order a line belongs to, so the service can check that order before the line changes. */
    public Optional<Long> orderIdOfLine(long lineId) {
        return db.queryOne("SELECT purchase_order_id FROM purchase_order_lines WHERE id = ?", rs -> rs.getLong(1), lineId);
    }

    public List<LineRow> findLines(long orderId) {
        return db.query("SELECT l.id, l.product_id, p.sku, p.name, l.qty_ordered, l.unit_cost_cents, "
                        + "COALESCE((SELECT SUM(r.qty) FROM goods_receipt_lines r "
                        + "WHERE r.purchase_order_line_id = l.id), 0) AS qty_received "
                        + "FROM purchase_order_lines l JOIN products p ON p.id = l.product_id "
                        + "WHERE l.purchase_order_id = ? ORDER BY l.id",
                PurchaseRepository::mapLine, orderId);
    }

    public long insertReceipt(long orderId, String invoiceNo, long userId, Instant receivedAt) {
        return db.insert("INSERT INTO goods_receipts (purchase_order_id, invoice_no, received_by, received_at) "
                + "VALUES (?, ?, ?, ?)", orderId, invoiceNo, userId, receivedAt);
    }

    public void insertReceiptLine(long receiptId, long orderLineId, int qty, long unitCostCents) {
        db.update("INSERT INTO goods_receipt_lines (goods_receipt_id, purchase_order_line_id, qty, unit_cost_cents) "
                + "VALUES (?, ?, ?, ?)", receiptId, orderLineId, qty, unitCostCents);
    }

    public long countReceipts(long orderId) {
        return db.queryOne("SELECT COUNT(*) FROM goods_receipts WHERE purchase_order_id = ?", rs -> rs.getLong(1), orderId)
                .orElse(0L);
    }

    private static OrderRow mapOrder(ResultSet rs) throws SQLException {
        return new OrderRow(
                rs.getLong("id"),
                rs.getLong("supplier_id"),
                rs.getString("supplier_name"),
                rs.getString("note"),
                Columns.instant(rs, "created_at"),
                Columns.instant(rs, "ordered_at"),
                Columns.instant(rs, "cancelled_at"));
    }

    private static LineRow mapLine(ResultSet rs) throws SQLException {
        return new LineRow(
                rs.getLong("id"),
                rs.getLong("product_id"),
                rs.getString("sku"),
                rs.getString("name"),
                rs.getInt("qty_ordered"),
                rs.getLong("unit_cost_cents"),
                rs.getInt("qty_received"));
    }
}
