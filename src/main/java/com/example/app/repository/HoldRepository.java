package com.example.app.repository;

import com.example.app.db.Columns;
import com.example.app.db.Database;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Parked carts. A held cart is only its lines; prices and stock are read again when it is recalled. */
public final class HoldRepository {

    public record HoldRow(long id, String label, long userId, Instant createdAt, int lineCount) {
    }

    public record HoldLine(long productId, int qty, int discountBp) {
    }

    private final Database db;

    public HoldRepository(Database db) {
        this.db = db;
    }

    public long insertHold(String label, long userId, Instant createdAt) {
        return db.insert("INSERT INTO held_carts (label, user_id, created_at) VALUES (?, ?, ?)", label, userId, createdAt);
    }

    public void insertLine(long holdId, long productId, int qty, int discountBp) {
        db.update("INSERT INTO held_cart_lines (held_cart_id, product_id, qty, discount_bp) VALUES (?, ?, ?, ?)",
                holdId, productId, qty, discountBp);
    }

    public List<HoldRow> findAll() {
        return db.query("SELECT h.id, h.label, h.user_id, h.created_at, COUNT(l.id) AS line_count "
                + "FROM held_carts h LEFT JOIN held_cart_lines l ON l.held_cart_id = h.id "
                + "GROUP BY h.id, h.label, h.user_id, h.created_at ORDER BY h.created_at, h.id", HoldRepository::mapRow);
    }

    public Optional<HoldRow> findById(long id) {
        return db.queryOne("SELECT h.id, h.label, h.user_id, h.created_at, COUNT(l.id) AS line_count "
                + "FROM held_carts h LEFT JOIN held_cart_lines l ON l.held_cart_id = h.id WHERE h.id = ? "
                + "GROUP BY h.id, h.label, h.user_id, h.created_at", HoldRepository::mapRow, id);
    }

    public List<HoldLine> findLines(long holdId) {
        return db.query("SELECT product_id, qty, discount_bp FROM held_cart_lines WHERE held_cart_id = ? ORDER BY id",
                rs -> new HoldLine(rs.getLong(1), rs.getInt(2), rs.getInt(3)), holdId);
    }

    public void delete(long holdId) {
        db.update("DELETE FROM held_cart_lines WHERE held_cart_id = ?", holdId);
        db.update("DELETE FROM held_carts WHERE id = ?", holdId);
    }

    private static HoldRow mapRow(ResultSet rs) throws SQLException {
        return new HoldRow(
                rs.getLong("id"),
                rs.getString("label"),
                rs.getLong("user_id"),
                Columns.instant(rs, "created_at"),
                rs.getInt("line_count"));
    }
}
