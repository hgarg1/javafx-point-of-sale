package com.example.app.repository;

import com.example.app.db.Database;
import com.example.app.domain.Role;
import com.example.app.domain.User;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public final class UserRepository {

    /** A user plus the stored password hash, used only during login. */
    public record Credentials(User user, String passwordHash) {
    }

    private static final String SELECT = "SELECT id, username, role, active, must_change_password FROM users";

    private final Database db;

    public UserRepository(Database db) {
        this.db = db;
    }

    public long insert(String username, String passwordHash, Role role, boolean mustChangePassword) {
        return db.insert("INSERT INTO users (username, password_hash, role, active, must_change_password) "
                + "VALUES (?, ?, ?, TRUE, ?)", username, passwordHash, role, mustChangePassword);
    }

    public Optional<User> findById(long id) {
        return db.queryOne(SELECT + " WHERE id = ?", UserRepository::map, id);
    }

    public Optional<Credentials> findCredentials(String username) {
        return db.queryOne("SELECT id, username, role, active, must_change_password, password_hash FROM users "
                + "WHERE username = ?", rs -> new Credentials(map(rs), rs.getString("password_hash")), username);
    }

    public Optional<String> findPasswordHash(long userId) {
        return db.queryOne("SELECT password_hash FROM users WHERE id = ?", rs -> rs.getString(1), userId);
    }

    public List<User> findAll() {
        return db.query(SELECT + " ORDER BY username", UserRepository::map);
    }

    public long count() {
        return db.queryOne("SELECT COUNT(*) FROM users", rs -> rs.getLong(1)).orElse(0L);
    }

    public void updatePassword(long id, String passwordHash, boolean mustChangePassword) {
        db.update("UPDATE users SET password_hash = ?, must_change_password = ? WHERE id = ?",
                passwordHash, mustChangePassword, id);
    }

    public void updateRole(long id, Role role) {
        db.update("UPDATE users SET role = ? WHERE id = ?", role, id);
    }

    public void updateActive(long id, boolean active) {
        db.update("UPDATE users SET active = ? WHERE id = ?", active, id);
    }

    private static User map(ResultSet rs) throws SQLException {
        return new User(
                rs.getLong("id"),
                rs.getString("username"),
                Role.valueOf(rs.getString("role")),
                rs.getBoolean("active"),
                rs.getBoolean("must_change_password"));
    }
}
