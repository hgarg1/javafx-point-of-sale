package com.example.app.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Applies numbered SQL scripts from this package's resources, in order. Each script runs once; the applied
 * version is recorded in schema_version. Add new migrations by appending to MIGRATIONS (never edit an applied script).
 */
public final class Migrator {

    private static final List<String> MIGRATIONS = List.of("V1__init.sql");

    private Migrator() {
    }

    public static void migrate(Database db) {
        db.execute("CREATE TABLE IF NOT EXISTS schema_version ("
                + "version INT PRIMARY KEY, script VARCHAR(100) NOT NULL, applied_at TIMESTAMP WITH TIME ZONE NOT NULL)");
        int current = db.queryOne("SELECT COALESCE(MAX(version), 0) FROM schema_version", rs -> rs.getInt(1)).orElse(0);

        for (int version = current + 1; version <= MIGRATIONS.size(); version++) {
            String script = MIGRATIONS.get(version - 1);
            int appliedVersion = version;
            db.inTransaction(connection -> {
                for (String statement : readStatements(script)) {
                    db.execute(statement);
                }
                db.update("INSERT INTO schema_version (version, script, applied_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                        appliedVersion, script);
                return null;
            });
        }
    }

    /** Splits a script into statements. Scripts must not contain ';' inside string literals. */
    static List<String> readStatements(String script) {
        String sql = readResource(script).lines()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));
        return Arrays.stream(sql.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** Scripts live next to this class, so they load correctly when the app runs as a named module. */
    private static String readResource(String fileName) {
        try (InputStream in = Migrator.class.getResourceAsStream(fileName)) {
            if (in == null) {
                throw new IllegalStateException("Missing migration script: " + fileName);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read migration script: " + fileName, e);
        }
    }
}
