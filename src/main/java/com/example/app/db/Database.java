package com.example.app.db;

import com.example.app.error.BusinessException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Owns the single JDBC connection and the transaction boundary.
 *
 * <p><b>Threading rule:</b> every call on this class must happen on the JavaFX application thread. The connection
 * is not thread-safe and the transaction depth counter is not synchronised. Background work (exports, backups)
 * must not touch the database from another thread; at this data size the work is short enough to run inline.
 *
 * <p>Constraint violations (SQLSTATE class 23) are reported to the user as {@link BusinessException} with a plain
 * message. Everything else is a {@link DataAccessException}, whose message includes the SQL for the log only.
 */
public final class Database implements AutoCloseable {

    @FunctionalInterface
    public interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private static final String CONFLICT_MESSAGE =
            "That change conflicts with existing data. Check for a duplicate name or code.";

    private final Connection connection;
    private int transactionDepth;

    public Database(String jdbcUrl) {
        try {
            this.connection = DriverManager.getConnection(jdbcUrl, "sa", "");
        } catch (SQLException e) {
            throw translate(e, "open database");
        }
    }

    /**
     * Runs the work atomically. Nested calls join the outer transaction, so a service can call another service
     * safely. Any exception, including business rule violations, rolls everything back.
     */
    public <T> T inTransaction(SqlWork<T> work) {
        if (transactionDepth > 0) {
            return unchecked(work, "transaction step");
        }
        try {
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw translate(e, "begin transaction");
        }
        transactionDepth++;
        try {
            T result = unchecked(work, "transaction step");
            connection.commit();
            return result;
        } catch (SQLException e) {
            rollbackQuietly();
            throw translate(e, "commit transaction");
        } catch (RuntimeException e) {
            rollbackQuietly();
            throw e;
        } finally {
            transactionDepth--;
            restoreAutoCommit();
        }
    }

    public void runInTransaction(Runnable work) {
        inTransaction(connection -> {
            work.run();
            return null;
        });
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw translate(e, sql);
        }
    }

    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> rows = query(sql, mapper, params);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Executes INSERT/UPDATE/DELETE and returns the affected row count. */
    public int update(String sql, Object... params) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw translate(e, sql);
        }
    }

    /** Executes an INSERT and returns the generated primary key. */
    public long insert(String sql, Object... params) {
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new DataAccessException("No generated key was returned for: " + sql, null);
                }
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw translate(e, sql);
        }
    }

    /** Runs raw SQL (DDL, scripts, BACKUP) on the connection. */
    public void execute(String sql) {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw translate(e, sql);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw translate(e, "close database");
        }
    }

    /**
     * Converts a JDBC failure into an exception the rest of the app can handle. A constraint violation becomes a
     * user-facing {@link BusinessException}; anything else keeps the SQL for the log and the original cause.
     */
    static RuntimeException translate(SQLException e, String context) {
        String state = e.getSQLState();
        if (state != null && state.startsWith("23")) {
            return new BusinessException(CONFLICT_MESSAGE);
        }
        return new DataAccessException("Database error during: " + context, e);
    }

    private <T> T unchecked(SqlWork<T> work, String context) {
        try {
            return work.apply(connection);
        } catch (SQLException e) {
            throw translate(e, context);
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException e) {
            throw new DataAccessException("Rollback failed", e);
        }
    }

    private void restoreAutoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            throw new DataAccessException("Cannot restore auto-commit", e);
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object value = params[i];
            if (value instanceof Instant instant) {
                value = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
            } else if (value instanceof Enum<?> enumValue) {
                value = enumValue.name();
            }
            ps.setObject(i + 1, value);
        }
    }
}
