package com.example.app.service;

import com.example.app.db.Database;
import com.example.app.error.BusinessException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Writes H2 backups (the {@code BACKUP TO} command) into a folder and keeps the most recent few.
 * A backup is a compressed script of the whole database. Restoring one is done with {@code RUNSCRIPT} into an
 * empty database; see the README.
 */
public final class BackupService {

    static final String PREFIX = "pos-backup-";
    static final int KEEP = 10;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault());

    private final Database db;
    private final Path folder;
    private final Clock clock;

    /** @param folder where backups are written, or null to disable backups (for in-memory test databases). */
    BackupService(Database db, Path folder, Clock clock) {
        this.db = db;
        this.folder = folder;
        this.clock = clock;
    }

    public boolean enabled() {
        return folder != null;
    }

    public Optional<Path> folder() {
        return Optional.ofNullable(folder);
    }

    /** Writes a backup now. {@code reason} appears in the file name, e.g. "before-upgrade". */
    public Optional<Path> backupNow(String reason) {
        if (folder == null) {
            return Optional.empty();
        }
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new BusinessException("Cannot create the backup folder " + folder);
        }
        String safeReason = reason.replaceAll("[^a-zA-Z0-9-]", "-");
        Path target = folder.resolve(PREFIX + STAMP.format(clock.instant()) + "-" + safeReason + ".zip");
        db.execute("BACKUP TO '" + sqlQuoted(target) + "'");
        prune();
        return Optional.of(target);
    }

    /** Backs up only if the newest backup is older than {@code interval} (or there is none). Used on exit. */
    public Optional<Path> backupIfDue(Duration interval) {
        if (folder == null) {
            return Optional.empty();
        }
        Optional<Instant> last = lastBackupTime();
        if (last.isPresent() && last.get().plus(interval).isAfter(clock.instant())) {
            return Optional.empty();
        }
        return backupNow("scheduled");
    }

    /** Backup files, newest first. */
    public List<Path> listBackups() {
        if (folder == null || !Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files
                    .filter(p -> p.getFileName().toString().startsWith(PREFIX))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            throw new BusinessException("Cannot read the backup folder " + folder);
        }
    }

    public Optional<Instant> lastBackupTime() {
        return listBackups().stream().findFirst().map(p -> {
            try {
                return Files.getLastModifiedTime(p).toInstant();
            } catch (IOException e) {
                return Instant.EPOCH;
            }
        });
    }

    private void prune() {
        List<Path> backups = listBackups();
        for (Path old : backups.subList(Math.min(KEEP, backups.size()), backups.size())) {
            try {
                Files.deleteIfExists(old);
            } catch (IOException ignored) {
                // A file we cannot delete is kept; the next backup tries again.
            }
        }
    }

    /** Single quotes doubled and forward slashes, so the path is safe inside the SQL string literal. */
    static String sqlQuoted(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
    }
}
