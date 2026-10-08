package com.example.app.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Slows down password guessing. After {@value #FREE_FAILURES} failed attempts for a username, further attempts are
 * refused for a period that doubles with each failure (30 s, 60 s, ... up to 5 minutes). A successful login clears
 * the count. The count is kept in memory, so restarting the app clears it; the audit log keeps the history.
 */
final class LoginGuard {

    static final int FREE_FAILURES = 5;
    static final Duration FIRST_LOCK = Duration.ofSeconds(30);
    static final Duration MAX_LOCK = Duration.ofMinutes(5);

    private record Attempts(int failures, Instant lockedUntil) {
    }

    private final Clock clock;
    private final Map<String, Attempts> byUsername = new HashMap<>();

    LoginGuard(Clock clock) {
        this.clock = clock;
    }

    /** How long this username must still wait before it may try again, if it is locked. */
    Optional<Duration> remainingLock(String username) {
        Attempts attempts = byUsername.get(username);
        if (attempts == null || attempts.lockedUntil() == null) {
            return Optional.empty();
        }
        Duration left = Duration.between(clock.instant(), attempts.lockedUntil());
        return left.isPositive() ? Optional.of(left) : Optional.empty();
    }

    /** Records a failed attempt. Returns the lock that was just applied, if this failure caused one. */
    Optional<Duration> recordFailure(String username) {
        int failures = byUsername.getOrDefault(username, new Attempts(0, null)).failures() + 1;
        if (failures < FREE_FAILURES) {
            byUsername.put(username, new Attempts(failures, null));
            return Optional.empty();
        }
        // The fifth failure locks for FIRST_LOCK; each later failure doubles it, capped at MAX_LOCK.
        int doublings = Math.min(failures - FREE_FAILURES, 10);
        Duration lock = FIRST_LOCK.multipliedBy(1L << doublings);
        if (lock.compareTo(MAX_LOCK) > 0) {
            lock = MAX_LOCK;
        }
        byUsername.put(username, new Attempts(failures, clock.instant().plus(lock)));
        return Optional.of(lock);
    }

    void recordSuccess(String username) {
        byUsername.remove(username);
    }
}
