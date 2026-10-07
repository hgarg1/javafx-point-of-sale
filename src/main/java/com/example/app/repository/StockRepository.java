package com.example.app.repository;

import com.example.app.db.Columns;
import com.example.app.db.Database;
import com.example.app.domain.MovementType;
import com.example.app.domain.StockMovement;

import java.time.Instant;
import java.util.List;

public final class StockRepository {

    private final Database db;

    public StockRepository(Database db) {
        this.db = db;
    }

    public long insertMovement(long productId, MovementType type, int qtyDelta, String reason,
                               long userId, Long refSaleId, Instant createdAt) {
        return db.insert("INSERT INTO stock_movements (product_id, movement_type, qty_delta, reason, user_id, "
                        + "ref_sale_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                productId, type, qtyDelta, reason, userId, refSaleId, createdAt);
    }

    public List<StockMovement> findByProduct(long productId) {
        return db.query("SELECT id, product_id, movement_type, qty_delta, reason, user_id, ref_sale_id, created_at "
                + "FROM stock_movements WHERE product_id = ? ORDER BY created_at DESC, id DESC", rs ->
                new StockMovement(
                        rs.getLong("id"),
                        rs.getLong("product_id"),
                        MovementType.valueOf(rs.getString("movement_type")),
                        rs.getInt("qty_delta"),
                        rs.getString("reason"),
                        rs.getLong("user_id"),
                        Columns.longOrNull(rs, "ref_sale_id"),
                        Columns.instant(rs, "created_at")), productId);
    }

    /** Sum of all movement deltas for a product. Should always equal products.stock_qty. */
    public long sumDeltas(long productId) {
        return db.queryOne("SELECT COALESCE(SUM(qty_delta), 0) FROM stock_movements WHERE product_id = ?",
                rs -> rs.getLong(1), productId).orElse(0L);
    }
}
