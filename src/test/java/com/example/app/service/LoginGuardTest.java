package com.example.app.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginGuardTest {

    /** A clock the test moves by hand. */
    private static final class ManualClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final ManualClock clock = new ManualClock();
    private final LoginGuard guard = new LoginGuard(clock);

    @Test
    void theFirstFourFailuresDoNotLock() {
        for (int i = 0; i < 4; i++) {
            assertEquals(Optional.empty(), guard.recordFailure("sam"));
        }
        assertFalse(guard.remainingLock("sam").isPresent());
    }

    @Test
    void theFifthFailureLocksForThirtySeconds() {
        for (int i = 0; i < 4; i++) {
            guard.recordFailure("sam");
        }
        assertEquals(Optional.of(Duration.ofSeconds(30)), guard.recordFailure("sam"));
        assertEquals(Duration.ofSeconds(30), guard.remainingLock("sam").orElseThrow());
    }

    @Test
    void theLockEndsWhenItsDurationHasPassed() {
        failFiveTimes("sam");

        clock.advance(Duration.ofSeconds(29));
        assertTrue(guard.remainingLock("sam").isPresent());

        clock.advance(Duration.ofSeconds(2));
        assertFalse(guard.remainingLock("sam").isPresent());
    }

    @Test
    void eachFurtherFailureDoublesTheLockUpToFiveMinutes() {
        failFiveTimes("sam");
        assertEquals(Duration.ofSeconds(60), guard.recordFailure("sam").orElseThrow());
        assertEquals(Duration.ofSeconds(120), guard.recordFailure("sam").orElseThrow());
        assertEquals(Duration.ofSeconds(240), guard.recordFailure("sam").orElseThrow());
        assertEquals(Duration.ofMinutes(5), guard.recordFailure("sam").orElseThrow());
        assertEquals(Duration.ofMinutes(5), guard.recordFailure("sam").orElseThrow());
    }

    @Test
    void aSuccessfulLoginClearsTheCount() {
        for (int i = 0; i < 4; i++) {
            guard.recordFailure("sam");
        }
        guard.recordSuccess("sam");

        assertEquals(Optional.empty(), guard.recordFailure("sam"));
    }

    @Test
    void locksAreKeptPerUsername() {
        failFiveTimes("sam");
        assertFalse(guard.remainingLock("maria").isPresent());
    }

    private void failFiveTimes(String username) {
        for (int i = 0; i < 5; i++) {
            guard.recordFailure(username);
        }
    }
}
