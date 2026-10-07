package com.example.app.repository;

import com.example.app.db.Columns;
import com.example.app.db.Database;
import com.example.app.domain.Shift;

import java.time.Instant;
import java.util.Optional;

public final class ShiftRepository {

    private static final String SELECT = "SELECT id, user_id, opened_at, closed_at, opening_float_cents, "
            + "counted_cash_cents, expected_cash_cents FROM shifts";

    private final Database db;

    public ShiftRepository(Database db) {
        this.db = db;
    }

    public long insert(long userId, Instant openedAt, long openingFloatCents) {
        return db.insert("INSERT INTO shifts (user_id, opened_at, opening_float_cents) VALUES (?, ?, ?)",
                userId, openedAt, openingFloatCents);
    }

    public Optional<Shift> findOpenByUser(long userId) {
        return db.queryOne(SELECT + " WHERE user_id = ? AND closed_at IS NULL", ShiftRepository::map, userId);
    }

    public Optional<Shift> findById(long id) {
        return db.queryOne(SELECT + " WHERE id = ?", ShiftRepository::map, id);
    }

    public void close(long id, Instant closedAt, long countedCashCents, long expectedCashCents) {
        db.update("UPDATE shifts SET closed_at = ?, counted_cash_cents = ?, expected_cash_cents = ? WHERE id = ?",
                closedAt, countedCashCents, expectedCashCents, id);
    }

    private static Shift map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Shift(
                rs.getLong("id"),
                rs.getLong("user_id"),
                Columns.instant(rs, "opened_at"),
                Columns.instant(rs, "closed_at"),
                rs.getLong("opening_float_cents"),
                Columns.longOrNull(rs, "counted_cash_cents"),
                Columns.longOrNull(rs, "expected_cash_cents"));
    }
}
