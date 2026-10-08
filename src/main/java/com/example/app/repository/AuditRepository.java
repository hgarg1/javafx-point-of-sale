package com.example.app.repository;

import com.example.app.db.Columns;
import com.example.app.db.Database;
import com.example.app.domain.AuditEntry;

import java.time.Instant;
import java.util.List;

public final class AuditRepository {

    private final Database db;

    public AuditRepository(Database db) {
        this.db = db;
    }

    /** Values are cut to the column sizes so an unusually long name can never fail the business action. */
    public long insert(Long userId, String action, String entity, String entityId, String detail, Instant at) {
        return db.insert("INSERT INTO audit_log (user_id, action, entity, entity_id, detail, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", userId, action, entity, truncate(entityId, 64),
                truncate(detail, 1000), at);
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    public List<AuditEntry> findRecent(int limit) {
        return db.query("SELECT id, user_id, action, entity, entity_id, detail, created_at FROM audit_log "
                + "ORDER BY id DESC LIMIT ?", rs -> new AuditEntry(
                rs.getLong("id"),
                Columns.longOrNull(rs, "user_id"),
                rs.getString("action"),
                rs.getString("entity"),
                rs.getString("entity_id"),
                rs.getString("detail"),
                Columns.instant(rs, "created_at")), limit);
    }
}
