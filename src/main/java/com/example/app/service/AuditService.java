package com.example.app.service;

import com.example.app.domain.Role;
import com.example.app.domain.User;
import com.example.app.repository.AuditRepository;
import com.example.app.repository.AuditRepository.AuditEntry;

import java.time.Instant;
import java.util.List;

/** Append-only log of sensitive actions. Records the session user automatically. */
public final class AuditService {

    private final AuditRepository audit;
    private final Session session;

    AuditService(AuditRepository audit, Session session) {
        this.audit = audit;
        this.session = session;
    }

    public void record(String action, String entity, String entityId, String detail) {
        Long userId = session.current().map(User::id).orElse(null);
        audit.insert(userId, action, entity, entityId, detail, Instant.now());
    }

    public List<AuditEntry> recent(int limit) {
        session.requireAtLeast(Role.MANAGER);
        return audit.findRecent(limit);
    }
}
