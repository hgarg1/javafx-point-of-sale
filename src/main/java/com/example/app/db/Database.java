package com.example.app.db;

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
 * The app is single-terminal and runs its database work on the JavaFX thread, so one connection is enough.
 */
public final class Database implements AutoCloseable {

    @FunctionalInterface
    public interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(java.sql.ResultSet rs) throws SQLException;
    }

    private final Connection connection;
    private int transactionDepth;

    public Database(String jdbcUrl) {
        try {
            this.connection = DriverManager.getConnection(jdbcUrl, "sa", "");
        } catch (SQLException e) {
            throw new DataAccessException("Cannot open database " + jdbcUrl, e);
        }
    }

    /**
     * Runs the work atomically. Nested calls join the outer transaction, so a service can call another service
     * safely. Any exception (including business rule violations) rolls everything back.
     */
    public <T> T inTransaction(SqlWork<T> work) {
        if (transactionDepth > 0) {
            return unchecked(work);
        }
        try {
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new DataAccessException("Cannot begin transaction", e);
        }
        transactionDepth++;
        try {
            T result = unchecked(work);
            connection.commit();
            return result;
        } catch (SQLException e) {
            rollbackQuietly();
            throw new DataAccessException("Cannot commit transaction", e);
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
            throw new DataAccessException("Query failed: " + sql, e);
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
            throw new DataAccessException("Update failed: " + sql, e);
        }
    }

    /** Executes an INSERT and returns the generated primary key. */
    public long insert(String sql, Object... params) {
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new DataAccessException("No generated key for: " + sql, null);
                }
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Insert failed: " + sql, e);
        }
    }

    /** Runs raw SQL (DDL, scripts) on the connection. */
    public void execute(String sql) {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new DataAccessException("Statement failed: " + sql, e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new DataAccessException("Cannot close database", e);
        }
    }

    private <T> T unchecked(SqlWork<T> work) {
        try {
            return work.apply(connection);
        } catch (SQLException e) {
            throw new DataAccessException("SQL error: " + e.getMessage(), e);
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
