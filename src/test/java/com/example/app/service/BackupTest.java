package com.example.app.service;

import com.example.app.db.Database;
import com.example.app.db.Migrator;
import com.example.app.domain.ProductDraft;
import com.example.app.util.PasswordHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupTest {

    @Test
    void aBackupCanBeRestoredByPuttingItsDatabaseFileBackInPlace(@TempDir Path dir) throws IOException {
        try (AppServices app = new AppServices(TestServices.fileUrl(dir, "pos"), new PasswordHasher(1_000),
                dir.resolve("backups"))) {
            app.auth().completeSetup("owner", "owner-pass-1");
            app.inventory().createProduct(new ProductDraft("BAK-1", null, "Backed up", null, null, 100, 50, 0, 0, true), 3);
            Path backup = app.backups().backupNow("manual").orElseThrow();
            assertTrue(Files.exists(backup));

            // Restoring is a file copy: H2 backs up the raw database file inside the zip.
            try (ZipFile zip = new ZipFile(backup.toFile())) {
                ZipEntry entry = zip.stream().filter(e -> e.getName().endsWith(".mv.db")).findFirst().orElseThrow();
                Files.copy(zip.getInputStream(entry), dir.resolve("restored.mv.db"));
            }

            try (Database restored = new Database(TestServices.fileUrl(dir, "restored"))) {
                assertEquals(1, restored.queryOne("SELECT COUNT(*) FROM products", rs -> rs.getLong(1)).orElseThrow());
                assertEquals(3, restored.queryOne("SELECT stock_qty FROM products", rs -> rs.getLong(1)).orElseThrow());
            }
        }
    }

    @Test
    void anUpgradeOfExistingDataIsPrecededByABackupAndKeepsTheRows(@TempDir Path dir) throws Exception {
        String url = TestServices.fileUrl(dir, "pos");
        Path folder = dir.resolve("backups");
        try (Database older = new Database(url)) {
            Migrator.apply(older, Migrator.bundledScripts(), 1, null); // an installation from before V2
            older.update("INSERT INTO users (username, password_hash, role, active, must_change_password) "
                    + "VALUES ('Owner', 'x', 'ADMIN', TRUE, FALSE)");
        }

        try (AppServices upgraded = new AppServices(url, new PasswordHasher(1_000), folder)) {
            assertEquals(6, upgraded.database().queryOne("SELECT MAX(version) FROM schema_version",
                    rs -> rs.getInt(1)).orElseThrow());
        }

        try (Stream<Path> files = Files.list(folder)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().contains("before-upgrade")));
        }
        try (Database check = new Database(url)) {
            assertEquals("owner", check.queryOne("SELECT username FROM users", rs -> rs.getString(1)).orElseThrow());
        }
    }

    @Test
    void automaticBackupsAreTakenOnlyWhenDue(@TempDir Path dir) {
        try (Database db = new Database(TestServices.fileUrl(dir, "pos"))) {
            Migrator.migrate(db);
            BackupService backups = new BackupService(db, dir, Clock.systemUTC());

            assertTrue(backups.backupIfDue(Duration.ofHours(24)).isPresent());
            assertTrue(backups.backupIfDue(Duration.ofHours(24)).isEmpty(), "a backup from a moment ago is not due");
        }
    }

    @Test
    void onlyTheMostRecentBackupsAreKept(@TempDir Path dir) {
        try (Database db = new Database(TestServices.fileUrl(dir, "pos"))) {
            Migrator.migrate(db);
            Clock base = Clock.systemUTC();
            for (int i = 0; i < BackupService.KEEP + 2; i++) {
                new BackupService(db, dir, Clock.offset(base, Duration.ofSeconds(i))).backupNow("run" + i);
            }
            assertEquals(BackupService.KEEP, new BackupService(db, dir, base).listBackups().size());
        }
    }
}
