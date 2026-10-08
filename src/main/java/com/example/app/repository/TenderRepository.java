package com.example.app.repository;

import com.example.app.db.Database;
import com.example.app.domain.Tender;
import com.example.app.domain.TenderKind;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class TenderRepository {

    private static final String SELECT = "SELECT code, name, kind, enabled, sort_order, counts_in_drawer, requires_reference "
            + "FROM tenders";

    private final Database db;

    public TenderRepository(Database db) {
        this.db = db;
    }

    /** Every tender in the order it appears at the till. Turned-off tenders are included. */
    public List<Tender> findAll() {
        return db.query(SELECT + " ORDER BY sort_order, code", TenderRepository::map);
    }

    public Optional<Tender> find(String code) {
        return db.queryOne(SELECT + " WHERE code = ?", TenderRepository::map, code);
    }

    public long count() {
        return db.queryOne("SELECT COUNT(*) FROM tenders", rs -> rs.getLong(1)).orElse(0L);
    }

    public int maxSortOrder() {
        return db.queryOne("SELECT COALESCE(MAX(sort_order), 0) FROM tenders", rs -> rs.getInt(1)).orElse(0);
    }

    /** Whether another tender already has this name, ignoring case. {@code exceptCode} is the tender being renamed, if any. */
    public boolean nameTaken(String name, String exceptCode) {
        return db.queryOne("SELECT code FROM tenders WHERE UPPER(name) = ? AND code <> ?", rs -> rs.getString(1),
                name.toUpperCase(Locale.ROOT), exceptCode == null ? "" : exceptCode).isPresent();
    }

    public void insert(Tender tender) {
        db.update("INSERT INTO tenders (code, name, kind, enabled, sort_order, counts_in_drawer, requires_reference) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                tender.code(), tender.name(), tender.kind(), tender.enabled(), tender.sortOrder(),
                tender.countsInDrawer(), tender.requiresReference());
    }

    public void updateName(String code, String name) {
        db.update("UPDATE tenders SET name = ? WHERE code = ?", name, code);
    }

    public void updateEnabled(String code, boolean enabled) {
        db.update("UPDATE tenders SET enabled = ? WHERE code = ?", enabled, code);
    }

    public void updateCountsInDrawer(String code, boolean countsInDrawer) {
        db.update("UPDATE tenders SET counts_in_drawer = ? WHERE code = ?", countsInDrawer, code);
    }

    public void updateSortOrder(String code, int sortOrder) {
        db.update("UPDATE tenders SET sort_order = ? WHERE code = ?", sortOrder, code);
    }

    public void delete(String code) {
        db.update("DELETE FROM tenders WHERE code = ?", code);
    }

    /** How many payments and refunds name this tender. A tender in use can be turned off, but not deleted. */
    public long usageCount(String code) {
        return db.queryOne("SELECT (SELECT COUNT(*) FROM payments WHERE method = ?) + "
                        + "(SELECT COUNT(*) FROM refunds WHERE method = ?)",
                rs -> rs.getLong(1), code, code).orElse(0L);
    }

    /** The codes of the tenders whose cash counts in the drawer. */
    public Set<String> drawerCodes() {
        Set<String> codes = new HashSet<>();
        db.query("SELECT code FROM tenders WHERE counts_in_drawer = TRUE", rs -> {
            codes.add(rs.getString(1));
            return null;
        });
        return codes;
    }

    /** The kind of every tender, by code. */
    public Map<String, TenderKind> kinds() {
        Map<String, TenderKind> kinds = new HashMap<>();
        db.query("SELECT code, kind FROM tenders", rs -> {
            kinds.put(rs.getString(1), TenderKind.valueOf(rs.getString(2)));
            return null;
        });
        return kinds;
    }

    private static Tender map(ResultSet rs) throws SQLException {
        return new Tender(
                rs.getString("code"),
                rs.getString("name"),
                TenderKind.valueOf(rs.getString("kind")),
                rs.getBoolean("enabled"),
                rs.getInt("sort_order"),
                rs.getBoolean("counts_in_drawer"),
                rs.getBoolean("requires_reference"));
    }
}
