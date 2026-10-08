package com.example.app.service;

import com.example.app.domain.Role;
import com.example.app.domain.User;
import com.example.app.error.AccessDeniedException;

import java.util.Optional;

/**
 * The logged-in user for this terminal. Services check it before doing any work.
 *
 * <p>A user whose password was set by an administrator must choose a new one before anything else. Until they do,
 * {@link #requireUser()} refuses; only the password change itself uses {@link #requireUserForPasswordChange()}.
 */
public final class Session {

    private User current;

    public Optional<User> current() {
        return Optional.ofNullable(current);
    }

    /** The logged-in user, who has finished any forced password change. */
    public User requireUser() {
        User user = requireUserForPasswordChange();
        if (user.mustChangePassword()) {
            throw new AccessDeniedException("Choose a new password before continuing");
        }
        return user;
    }

    /** The logged-in user, even if a forced password change is still pending. */
    public User requireUserForPasswordChange() {
        if (current == null) {
            throw new AccessDeniedException("Please log in first");
        }
        return current;
    }

    /** Returns the current user if their role is at least {@code minimum}, otherwise throws. */
    public User requireAtLeast(Role minimum) {
        User user = requireUser();
        if (!user.role().atLeast(minimum)) {
            throw new AccessDeniedException("This action needs " + minimum + " access");
        }
        return user;
    }

    void start(User user) {
        this.current = user;
    }

    void end() {
        this.current = null;
    }
}
