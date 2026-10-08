package com.example.app.service;

import com.example.app.db.Database;
import com.example.app.domain.Role;
import com.example.app.domain.User;
import com.example.app.error.BusinessException;
import com.example.app.repository.UserRepository;
import com.example.app.util.PasswordHasher;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public final class AuthService {

    static final int MIN_PASSWORD_LENGTH = 8;
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,32}");

    private final Database db;
    private final UserRepository users;
    private final PasswordHasher hasher;
    private final Session session;
    private final AuditService audit;
    private final LoginGuard guard;

    AuthService(Database db, UserRepository users, PasswordHasher hasher, Session session, AuditService audit,
                LoginGuard guard) {
        this.db = db;
        this.users = users;
        this.hasher = hasher;
        this.session = session;
        this.audit = audit;
        this.guard = guard;
    }

    /** True on a database with no accounts yet. The first screen then asks for the owner's account. */
    public boolean needsSetup() {
        return users.count() == 0;
    }

    /**
     * Creates the first administrator and logs them in. Only possible while there are no accounts.
     * Unlike accounts made by an administrator, this one does not have to change its password.
     */
    public User completeSetup(String username, String password) {
        String name = normalizeUsername(username);
        validateUsername(name);
        validateNewPassword(password);
        if (!needsSetup()) {
            throw new BusinessException("This store is already set up. Log in instead.");
        }
        return db.inTransaction(c -> {
            long id = users.insert(name, hasher.hash(password), Role.ADMIN, false);
            User admin = users.findById(id).orElseThrow();
            session.start(admin);
            audit.record("SETUP", "user", String.valueOf(id), "first administrator " + name);
            return admin;
        });
    }

    public User login(String username, String password) {
        String name = normalizeUsername(username);
        Optional<Duration> locked = guard.remainingLock(name);
        if (locked.isPresent()) {
            throw new BusinessException("Too many failed attempts. Try again in " + seconds(locked.get()) + ".");
        }

        Optional<UserRepository.Credentials> creds = users.findCredentials(name)
                .filter(c -> hasher.verify(password == null ? "" : password, c.passwordHash()));
        if (creds.isEmpty()) {
            Optional<Duration> newLock = guard.recordFailure(name);
            audit.record("LOGIN_FAILED", "user", truncate(name, 64), null);
            newLock.ifPresent(lock -> audit.record("LOGIN_LOCKED", "user", truncate(name, 64),
                    "locked for " + seconds(lock)));
            throw new BusinessException("Invalid username or password");
        }

        User user = creds.get().user();
        if (!user.active()) {
            throw new BusinessException("This account is disabled");
        }
        guard.recordSuccess(name);
        session.start(user);
        users.updateLastLogin(user.id(), Instant.now());
        audit.record("LOGIN", "user", String.valueOf(user.id()), null);
        return user;
    }

    public void logout() {
        session.end();
    }

    /** Changes the logged-in user's own password. Also used for the forced change after an admin sets one. */
    public User changeOwnPassword(String currentPassword, String newPassword) {
        User user = session.requireUserForPasswordChange();
        String storedHash = users.findPasswordHash(user.id()).orElseThrow();
        if (!hasher.verify(currentPassword == null ? "" : currentPassword, storedHash)) {
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

    /** The name of an account, for showing who opened a shift or made a sale. Any logged-in user may ask. */
    public String usernameOf(long userId) {
        session.requireUser();
        return users.findById(userId).map(User::username).orElse("(removed user)");
    }

    public long createUser(String username, String temporaryPassword, Role role) {
        session.requireAtLeast(Role.ADMIN);
        String name = normalizeUsername(username);
        validateUsername(name);
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
     * Checks a manager's credentials for an override (a large discount, a refund) without changing the session.
     * Failed attempts count towards the same login lock as ordinary logins.
     */
    public User verifyApprover(String username, String password) {
        String name = normalizeUsername(username);
        Optional<Duration> locked = guard.remainingLock(name);
        if (locked.isPresent()) {
            throw new BusinessException("Too many failed attempts. Try again in " + seconds(locked.get()) + ".");
        }
        Optional<User> approver = users.findCredentials(name)
                .filter(c -> hasher.verify(password == null ? "" : password, c.passwordHash()))
                .map(UserRepository.Credentials::user)
                .filter(u -> u.active() && u.role().atLeast(Role.MANAGER));
        if (approver.isEmpty()) {
            guard.recordFailure(name);
            throw new BusinessException("Approver credentials are invalid or not a manager");
        }
        guard.recordSuccess(name);
        return approver.get();
    }

    /** Usernames are case-insensitive: stored and compared in lower case. */
    static String normalizeUsername(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private static void validateUsername(String name) {
        if (!USERNAME.matcher(name).matches()) {
            throw new BusinessException("Usernames are 3 to 32 letters, digits, dots, dashes or underscores");
        }
    }

    private static void validateNewPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new BusinessException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }

    private static long seconds(Duration duration) {
        return Math.max(1, duration.toSeconds());
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
