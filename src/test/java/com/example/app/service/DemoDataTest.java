package com.example.app.service;

import com.example.app.domain.Role;
import com.example.app.util.PasswordHasher;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DemoDataTest {
    @Test
    void demoCreatesUsableAccountsAndTradingDataAndDoesNotReseed() {
        try (AppServices app = new AppServices("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                new PasswordHasher(1000))) {
            DemoData.seed(app);
            assertTrue(app.session().current().isEmpty());
            assertEquals(Role.ADMIN, app.auth().login("owner", "owner-pass-1").role());
            assertEquals(25, app.inventory().listProducts().size());
            assertEquals(6, app.reports().salesBetween(LocalDate.now(), LocalDate.now()).size());
            assertThrows(IllegalStateException.class, () -> DemoData.seed(app));
            assertEquals(25, app.inventory().listProducts().size());
            app.auth().logout();
            assertEquals(Role.MANAGER, app.auth().login("maria", "maria-pass-1").role());
            app.auth().logout();
            var cashier = app.auth().login("sam", "sam-pass-1");
            assertEquals(Role.CASHIER, cashier.role());
            assertFalse(cashier.mustChangePassword());
        }
    }

    @Test
    void demoRefusesAnExistingStore() {
        try (AppServices app = TestServices.fresh()) {
            assertThrows(IllegalStateException.class, () -> DemoData.seed(app));
            app.auth().login(TestServices.OWNER, TestServices.OWNER_PASSWORD);
            assertTrue(app.inventory().listProducts().isEmpty());
            assertEquals(3, app.auth().listUsers().size());
        }
    }
}
