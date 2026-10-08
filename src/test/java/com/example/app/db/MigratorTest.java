package com.example.app.db;

import com.example.app.error.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MigratorTest {

    private Database db;

    @BeforeEach
    void open() {
        db = new Database("jdbc:h2:mem:" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1");
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    void appliesEveryBundledMigrationOnceAndIsSafeToRunAgain() {
        Migrator.migrate(db);
        Migrator.migrate(db);

        assertEquals(6, count("SELECT COUNT(*) FROM schema_version"));
        assertEquals(3, count("SELECT COUNT(*) FROM settings"));
    }

    @Test
    void aNewDatabaseIsNotBackedUpBeforeMigrating() {
        AtomicInteger backups = new AtomicInteger();
        Migrator.migrate(db, backups::incrementAndGet);
        assertEquals(0, backups.get());
    }

    @Test
    void anExistingDatabaseIsBackedUpOnceBeforeUpgrading() {
        Migrator.apply(db, Migrator.bundledScripts(), 1, null);
        AtomicInteger backups = new AtomicInteger();

        Migrator.migrate(db, backups::incrementAndGet);
        Migrator.migrate(db, backups::incrementAndGet);

        assertEquals(1, backups.get());
    }

    @Test
    void upgradeKeepsExistingRowsAndNormalisesUsernamesAndSkus() {
        Migrator.apply(db, Migrator.bundledScripts(), 1, null);
        db.update("INSERT INTO users (username, password_hash, role, active, must_change_password) "
                + "VALUES ('Sam', 'x', 'CASHIER', TRUE, FALSE)");
        db.update("INSERT INTO products (sku, name, price_cents, cost_cents, tax_rate_bp, stock_qty, reorder_level, active) "
                + "VALUES ('abc-1', 'Bun', 100, 50, 0, 0, 0, TRUE)");

        Migrator.migrate(db);

        assertEquals("sam", db.queryOne("SELECT username FROM users", rs -> rs.getString(1)).orElseThrow());
        assertEquals("ABC-1", db.queryOne("SELECT sku FROM products", rs -> rs.getString(1)).orElseThrow());
    }

    @Test
    void aFailedStepKeepsTheOldVersionAndCanBeRetriedOnceFixed() {
        Migrator.apply(db, Migrator.bundledScripts(), 1, null);
        Migrator.Script broken = new Migrator.Script(2, "V2__broken.sql",
                "CREATE TABLE IF NOT EXISTS demo_two (id INT); CREATE TABLE demo_bad (id INT,,)");

        assertThrows(MigrationException.class,
                () -> Migrator.apply(db, List.of(broken), 2, null));
        assertEquals(1, currentVersion(), "a failed step must not be recorded");

        Migrator.Script fixed = new Migrator.Script(2, "V2__fixed.sql",
                "CREATE TABLE IF NOT EXISTS demo_two (id INT); CREATE TABLE IF NOT EXISTS demo_bad (id INT)");
        Migrator.apply(db, List.of(fixed), 2, null);

        assertEquals(2, currentVersion());
        assertEquals(0, count("SELECT COUNT(*) FROM demo_bad"));
    }

    @Test
    void commentsAreIgnoredWhenSplittingScripts() {
        List<String> statements = Migrator.statementsOf(
                "-- a comment; with a semicolon\nCREATE TABLE t (id INT);\n-- trailing comment\n");
        assertEquals(List.of("CREATE TABLE t (id INT)"), statements);
    }

    /** An installation at V5 with real sales, payments and a refund. Every tender those rows name must still exist after V6. */
    @Test
    void upgradeToTenderConfigurationKeepsPaymentsAndRefundsAndNamesTheirTenders() {
        Migrator.apply(db, Migrator.bundledScripts(), 5, null);
        db.update("INSERT INTO users (username, password_hash, role, active, must_change_password) "
                + "VALUES ('owner', 'x', 'ADMIN', TRUE, FALSE)");
        db.update("INSERT INTO shifts (user_id, opened_at, opening_float_cents) VALUES (1, CURRENT_TIMESTAMP, 0)");
        db.update("INSERT INTO sales (receipt_no, shift_id, user_id, subtotal_cents, discount_cents, tax_cents, total_cents, "
                + "created_at) VALUES ('R000001', 1, 1, 500, 0, 0, 500, CURRENT_TIMESTAMP)");
        db.update("INSERT INTO payments (sale_id, method, applied_cents, tendered_cents, change_cents, provider_ref) "
                + "VALUES (1, 'CARD', 300, 300, 0, 'CARD-1')");
        db.update("INSERT INTO payments (sale_id, method, applied_cents, tendered_cents, change_cents, provider_ref) "
                + "VALUES (1, 'CASH', 200, 250, 50, NULL)");
        db.update("INSERT INTO refunds (sale_id, shift_id, user_id, approved_by, method, reason, amount_cents, created_at) "
                + "VALUES (1, 1, 1, 1, 'CASH', 'returned', 100, CURRENT_TIMESTAMP)");

        Migrator.migrate(db);

        assertEquals("Card", db.queryOne("SELECT tender_name FROM payments WHERE method = 'CARD'", rs -> rs.getString(1))
                .orElseThrow());
        assertEquals("Cash", db.queryOne("SELECT tender_name FROM refunds WHERE method = 'CASH'", rs -> rs.getString(1))
                .orElseThrow());
        assertEquals("CARD", db.queryOne("SELECT kind FROM tenders WHERE code = 'CARD'", rs -> rs.getString(1)).orElseThrow());
        assertEquals(2, count("SELECT COUNT(*) FROM payments"));
        assertThrows(BusinessException.class, () -> db.update("INSERT INTO payments (sale_id, method, applied_cents, "
                + "tendered_cents, change_cents) VALUES (1, 'NO_SUCH_TENDER', 100, 100, 0)"),
                "a payment must name a tender that exists");
    }

    @Test
    void bundledScriptsAreNumberedConsecutivelyFromOne() {
        assertEquals(List.of(1, 2, 3, 4, 5, 6), Migrator.bundledScripts().stream().map(Migrator.Script::version).toList());
    }

    private int currentVersion() {
        return (int) count("SELECT COALESCE(MAX(version), 0) FROM schema_version");
    }

    private long count(String sql) {
        return db.queryOne(sql, rs -> rs.getLong(1)).orElseThrow();
    }
}
