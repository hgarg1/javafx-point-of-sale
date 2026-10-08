package com.example.app.repository;

import com.example.app.db.Columns;
import com.example.app.db.Database;
import com.example.app.domain.Shift;

import java.sql.ResultSet;
import java.sql.SQLException;
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

    /** The shift that is open on this terminal, if any. There is at most one. */
    public Optional<Shift> findOpen() {
        return db.queryOne(SELECT + " WHERE closed_at IS NULL ORDER BY id", ShiftRepository::map);
    }

    public Optional<Shift> findById(long id) {
        return db.queryOne(SELECT + " WHERE id = ?", ShiftRepository::map, id);
    }

    public void close(long id, Instant closedAt, long countedCashCents, long expectedCashCents, long closedBy) {
        db.update("UPDATE shifts SET closed_at = ?, counted_cash_cents = ?, expected_cash_cents = ?, closed_by = ? "
                + "WHERE id = ?", closedAt, countedCashCents, expectedCashCents, closedBy, id);
    }

    private static Shift map(ResultSet rs) throws SQLException {
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
