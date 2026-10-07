package com.example.app.repository;

import com.example.app.db.Columns;
import com.example.app.db.Database;

import java.time.Instant;
import java.util.List;

public final class AuditRepository {

    public record AuditEntry(long id, Long userId, String action, String entity, String entityId, String detail,
                             Instant createdAt) {
    }

    private final Database db;

    public AuditRepository(Database db) {
        this.db = db;
    }

    public long insert(Long userId, String action, String entity, String entityId, String detail, Instant at) {
        return db.insert("INSERT INTO audit_log (user_id, action, entity, entity_id, detail, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", userId, action, entity, entityId, detail, at);
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
