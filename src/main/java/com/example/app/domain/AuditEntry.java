package com.example.app.domain;

import java.time.Instant;

/** One entry in the audit log, as the reports screen shows it. */
public record AuditEntry(long id, Long userId, String action, String entity, String entityId, String detail,
                         Instant createdAt) {
}
