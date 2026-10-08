package com.example.app.repository;

import com.example.app.db.Database;

import java.time.Instant;
import java.util.List;

/** The stored-value ledger. Entries are only ever added; a card's balance is the sum of its entries. */
public final class StoredValueRepository {

    private final Database db;

    public StoredValueRepository(Database db) {
        this.db = db;
    }

    public void insert(String account, String kind, long amountCents, Long saleId, Long refundId, Instant at) {
        db.update("INSERT INTO stored_value_entries (account_code, kind, amount_cents, sale_id, refund_id, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", account, kind, amountCents, saleId, refundId, at);
    }

    public long balance(String account) {
        return db.queryOne("SELECT COALESCE(SUM(amount_cents), 0) FROM stored_value_entries WHERE account_code = ?",
                rs -> rs.getLong(1), account).orElse(0L);
    }

    public boolean exists(String account) {
        return db.queryOne("SELECT COUNT(*) FROM stored_value_entries WHERE account_code = ?",
                rs -> rs.getLong(1), account).orElse(0L) > 0;
    }

    /** The gift card codes a sale issued, in the order they were issued. */
    public List<String> issuedBy(long saleId) {
        return db.query("SELECT account_code FROM stored_value_entries WHERE sale_id = ? AND kind = 'ISSUE' ORDER BY id",
                rs -> rs.getString(1), saleId);
    }
}
