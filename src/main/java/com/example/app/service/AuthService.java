package com.example.app.service;

import com.example.app.db.Database;
import com.example.app.domain.Role;
import com.example.app.domain.User;
import com.example.app.error.BusinessException;
import com.example.app.repository.UserRepository;
import com.example.app.util.PasswordHasher;

import java.util.List;

public final class AuthService {

    static final int MIN_PASSWORD_LENGTH = 8;
    static final String BOOTSTRAP_USERNAME = "admin";
    static final String BOOTSTRAP_PASSWORD = "admin";

    private final Database db;
    private final UserRepository users;
    private final PasswordHasher hasher;
    private final Session session;
    private final AuditService audit;

    AuthService(Database db, UserRepository users, PasswordHasher hasher, Session session, AuditService audit) {
        this.db = db;
        this.users = users;
        this.hasher = hasher;
        this.session = session;
        this.audit = audit;
    }

    /** On a fresh database, creates the admin account with a password that must be changed at first login. */
    public void ensureBootstrapAdmin() {
        db.runInTransaction(() -> {
            if (users.count() == 0) {
                users.insert(BOOTSTRAP_USERNAME, hasher.hash(BOOTSTRAP_PASSWORD), Role.ADMIN, true);
            }
        });
    }

    public User login(String username, String password) {
        UserRepository.Credentials creds = users.findCredentials(username.trim())
                .filter(c -> hasher.verify(password, c.passwordHash()))
                .orElseThrow(() -> new BusinessException("Invalid username or password"));
        if (!creds.user().active()) {
            throw new BusinessException("This account is disabled");
        }
        session.start(creds.user());
        audit.record("LOGIN", "user", String.valueOf(creds.user().id()), null);
        return creds.user();
    }

    public void logout() {
        session.end();
    }

    /** Changes the logged-in user's own password. Also used for the forced change on first login. */
    public User changeOwnPassword(String currentPassword, String newPassword) {
        User user = session.requireUser();
        String storedHash = users.findPasswordHash(user.id()).orElseThrow();
        if (!hasher.verify(currentPassword, storedHash)) {
            throw new BusinessException("Current password is incorrect");
        }
        validateNewPassword(newPassword);
        if (newPassword.equals(currentPassword)) {
            throw new BusinessException("Choose a password different from the current one");
        }
        return db.inTransaction(c -> {
            users.updatePassword(user.id(), hasher.hash(newPassword), false);
            User updated = new User(user.id(), user.username(), user.role(), user.active(), false);
            session.start(updated);
            audit.record("PASSWORD_CHANGE", "user", String.valueOf(user.id()), null);
            return updated;
        });
    }

    public List<User> listUsers() {
        session.requireAtLeast(Role.ADMIN);
        return users.findAll();
    }

    public long createUser(String username, String temporaryPassword, Role role) {
        session.requireAtLeast(Role.ADMIN);
        String name = username == null ? "" : username.trim();
        if (name.isEmpty()) {
            throw new BusinessException("Username is required");
        }
        validateNewPassword(temporaryPassword);
        return db.inTransaction(c -> {
            if (users.findCredentials(name).isPresent()) {
                throw new BusinessException("Username '" + name + "' is already taken");
            }
            long id = users.insert(name, hasher.hash(temporaryPassword), role, true);
            audit.record("CREATE_USER", "user", String.valueOf(id), name + " as " + role);
            return id;
        });
    }

    public void changeRole(long userId, Role role) {
        User admin = session.requireAtLeast(Role.ADMIN);
        if (admin.id() == userId) {
            throw new BusinessException("You cannot change your own role");
        }
        db.runInTransaction(() -> {
            users.updateRole(userId, role);
            audit.record("CHANGE_ROLE", "user", String.valueOf(userId), "to " + role);
        });
    }

    public void setActive(long userId, boolean active) {
        User admin = session.requireAtLeast(Role.ADMIN);
        if (admin.id() == userId) {
            throw new BusinessException("You cannot disable your own account");
        }
        db.runInTransaction(() -> {
            users.updateActive(userId, active);
            audit.record(active ? "ENABLE_USER" : "DISABLE_USER", "user", String.valueOf(userId), null);
        });
    }

    /** Sets a temporary password; the user must change it at next login. */
    public void resetPassword(long userId, String temporaryPassword) {
        session.requireAtLeast(Role.ADMIN);
        validateNewPassword(temporaryPassword);
        db.runInTransaction(() -> {
            users.updatePassword(userId, hasher.hash(temporaryPassword), true);
            audit.record("RESET_PASSWORD", "user", String.valueOf(userId), null);
        });
    }

    /**
     * Checks a manager's credentials for an override (such as a large discount) without changing the session.
     */
    public User verifyApprover(String username, String password) {
        return users.findCredentials(username == null ? "" : username.trim())
                .filter(c -> c.user().active() && c.user().role().atLeast(Role.MANAGER))
                .filter(c -> hasher.verify(password, c.passwordHash()))
                .map(UserRepository.Credentials::user)
                .orElseThrow(() -> new BusinessException("Approver credentials are invalid or not a manager"));
    }

    private static void validateNewPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new BusinessException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }
}
