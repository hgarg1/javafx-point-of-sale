package com.example.app.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void appliesEveryMigrationOnceAndIsSafeToRunAgain() {
        Migrator.migrate(db);
        Migrator.migrate(db);

        long applied = db.queryOne("SELECT COUNT(*) FROM schema_version", rs -> rs.getLong(1)).orElseThrow();
        assertEquals(1, applied);
        long settings = db.queryOne("SELECT COUNT(*) FROM settings", rs -> rs.getLong(1)).orElseThrow();
        assertEquals(3, settings);
    }

    @Test
    void splitsScriptsIntoStatementsIgnoringComments() {
        var statements = Migrator.readStatements("V1__init.sql");
        assertFalse(statements.isEmpty());
        assertTrue(statements.stream().noneMatch(s -> s.startsWith("--")));
    }
}
