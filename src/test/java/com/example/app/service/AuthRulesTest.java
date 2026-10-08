package com.example.app.service;

import com.example.app.domain.Role;
import com.example.app.domain.User;
import com.example.app.error.AccessDeniedException;
import com.example.app.error.BusinessException;
import com.example.app.util.PasswordHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.example.app.service.TestServices.CASHIER;
import static com.example.app.service.TestServices.CASHIER_PASSWORD;
import static com.example.app.service.TestServices.OWNER;
import static com.example.app.service.TestServices.OWNER_PASSWORD;
import static com.example.app.service.TestServices.TEMPORARY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Accounts, passwords, the first-run setup, and the login lock. */
class AuthRulesTest {

    private AppServices app;

    @BeforeEach
    void setUp() {
        app = TestServices.fresh();
    }

    @AfterEach
    void tearDown() {
        app.close();
    }

    @Test
    void setupCreatesTheOwnerOnceWithoutAForcedPasswordChange() {
        try (AppServices fresh = new AppServices(TestServices.url(), new PasswordHasher(1_000))) {
            assertTrue(fresh.auth().needsSetup());

            User owner = fresh.auth().completeSetup("Owner", "owner-pass-1");

            assertEquals("owner", owner.username());
            assertFalse(owner.mustChangePassword());
            assertFalse(fresh.auth().needsSetup());
            assertThrows(BusinessException.class, () -> fresh.auth().completeSetup("second", "second-pass-1"));
        }
    }

    @Test
    void anAccountMadeByAnAdministratorMustChangeThePasswordBeforeAnythingElse() {
        app.auth().login(OWNER, OWNER_PASSWORD);
        app.auth().createUser("ana", TEMPORARY, Role.CASHIER);
        app.auth().logout();

        User ana = app.auth().login("ana", TEMPORARY);
        assertTrue(ana.mustChangePassword());
        assertThrows(AccessDeniedException.class, () -> app.inventory().listProducts());

        app.auth().changeOwnPassword(TEMPORARY, "ana-pass-1");
        assertEquals(0, app.inventory().listProducts().size());
    }

    @Test
    void wrongPasswordGivesOneGenericMessage() {
        BusinessException e = assertThrows(BusinessException.class, () -> app.auth().login(OWNER, "nope-nope"));
        assertEquals("Invalid username or password", e.getMessage());
    }

    @Test
    void fiveFailedLoginsLockTheAccountForAWhile() {
        for (int i = 0; i < 5; i++) {
            BusinessException e = assertThrows(BusinessException.class, () -> app.auth().login(CASHIER, "wrong-pass"));
            assertEquals("Invalid username or password", e.getMessage());
        }

        BusinessException locked = assertThrows(BusinessException.class,
                () -> app.auth().login(CASHIER, CASHIER_PASSWORD));
        assertTrue(locked.getMessage().startsWith("Too many failed attempts"), locked.getMessage());

        // The lock belongs to that username only.
        assertEquals(OWNER, app.auth().login(OWNER, OWNER_PASSWORD).username());
        assertTrue(app.audit().recent(20).stream().anyMatch(e -> "LOGIN_LOCKED".equals(e.action())));
    }

    @Test
    void usernamesAreCaseInsensitive() {
        app.auth().login(OWNER, OWNER_PASSWORD);
        app.auth().createUser("Lee", TEMPORARY, Role.CASHIER);
        app.auth().logout();

        assertEquals("lee", app.auth().login("LEE", TEMPORARY).username());
    }

    @Test
    void invalidUsernamesAreRejected() {
        app.auth().login(OWNER, OWNER_PASSWORD);
        BusinessException e = assertThrows(BusinessException.class,
                () -> app.auth().createUser("bad name!", TEMPORARY, Role.CASHIER));
        assertEquals("Usernames are 3 to 32 letters, digits, dots, dashes or underscores", e.getMessage());
    }

    @Test
    void passwordsMustBeAtLeastEightCharacters() {
        app.auth().login(OWNER, OWNER_PASSWORD);
        BusinessException e = assertThrows(BusinessException.class,
                () -> app.auth().createUser("shorty", "abc", Role.CASHIER));
        assertEquals("Password must be at least 8 characters", e.getMessage());
    }

    @Test
    void aDisabledAccountCannotLogIn() {
        long cashierId = TestServices.idOf(app, CASHIER);
        app.auth().login(OWNER, OWNER_PASSWORD);
        app.auth().setActive(cashierId, false);
        app.auth().logout();

        BusinessException e = assertThrows(BusinessException.class, () -> app.auth().login(CASHIER, CASHIER_PASSWORD));
        assertEquals("This account is disabled", e.getMessage());
    }

    @Test
    void aCashierCannotApproveDiscountsOrRefunds() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> app.auth().verifyApprover(CASHIER, CASHIER_PASSWORD));
        assertEquals("Approver credentials are invalid or not a manager", e.getMessage());
    }
}
