package com.example.app.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Applies numbered SQL scripts in version order. Each script runs in its own transaction together with its
 * schema_version row.
 *
 * <p><b>H2 commits DDL implicitly</b>, so a script that fails halfway can leave some of its tables behind. The
 * rules that keep upgrades recoverable:
 * <ul>
 *   <li>Take a backup before upgrading an existing database (the caller passes a {@code beforeUpgrade} hook).</li>
 *   <li>Write DDL so it can run twice: {@code IF NOT EXISTS} everywhere.</li>
 *   <li>Never edit a script that has shipped. Add a new version instead.</li>
 *   <li>Scripts are split on {@code ;}, so they must not contain a semicolon inside a string literal, and
 *       comment lines must start with {@code --}.</li>
 * </ul>
 */
public final class Migrator {

    /** One migration. Versions start at 1 and are consecutive. */
    public record Script(int version, String name, String sql) {
    }

    private static final List<String> RESOURCES = List.of("V1__init.sql", "V2__hardening.sql", "V3__tenders.sql",
            "V4__holds_and_voids.sql", "V5__purchasing.sql", "V6__tender_configuration.sql");

    private Migrator() {
    }

    /** Applies every pending bundled migration, without a backup hook. */
    public static void migrate(Database db) {
        apply(db, bundledScripts(), Integer.MAX_VALUE, null);
    }

    /** Applies pending bundled migrations. {@code beforeUpgrade} runs once, before the first one, for existing data. */
    public static void migrate(Database db, Runnable beforeUpgrade) {
        apply(db, bundledScripts(), Integer.MAX_VALUE, beforeUpgrade);
    }

    /** The bundled migrations, in version order. */
    public static List<Script> bundledScripts() {
        return java.util.stream.IntStream.range(0, RESOURCES.size())
                .mapToObj(i -> new Script(i + 1, RESOURCES.get(i), readResource(RESOURCES.get(i))))
                .toList();
    }

    /**
     * Applies pending scripts up to and including {@code upToVersion}.
     *
     * @param beforeUpgrade run once before the first pending script when the database already has a version
     *                      recorded (existing data). Not run for a new, empty database. May be null.
     */
    public static void apply(Database db, List<Script> scripts, int upToVersion, Runnable beforeUpgrade) {
        db.execute("CREATE TABLE IF NOT EXISTS schema_version ("
                + "version INT PRIMARY KEY, script VARCHAR(100) NOT NULL, applied_at TIMESTAMP WITH TIME ZONE NOT NULL)");
        int current = currentVersion(db);

        List<Script> pending = scripts.stream()
                .filter(s -> s.version() > current && s.version() <= upToVersion)
                .sorted(Comparator.comparingInt(Script::version))
                .toList();
        if (pending.isEmpty()) {
            return;
        }
        if (current > 0 && beforeUpgrade != null) {
            beforeUpgrade.run();
        }
        for (Script script : pending) {
            try {
                db.runInTransaction(() -> {
                    for (String statement : statementsOf(script.sql())) {
                        db.execute(statement);
                    }
                    db.update("INSERT INTO schema_version (version, script, applied_at) "
                            + "VALUES (?, ?, CURRENT_TIMESTAMP)", script.version(), script.name());
                });
            } catch (RuntimeException e) {
                throw new MigrationException("Upgrading the database failed at " + script.name()
                        + ". The steps before it were kept. A backup was taken before the upgrade if the database "
                        + "already had data; see the backups folder.", e);
            }
        }
    }

    private static int currentVersion(Database db) {
        return db.queryOne("SELECT COALESCE(MAX(version), 0) FROM schema_version", rs -> rs.getInt(1)).orElse(0);
    }

    /** Splits a script into statements, ignoring comment lines. */
    static List<String> statementsOf(String script) {
        String sql = script.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));
        return Arrays.stream(sql.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** Scripts live in this class's package, so they load correctly when the app runs as a named module. */
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
