package com.example.app.service;

import com.example.app.domain.MoneyFormat;
import com.example.app.error.AccessDeniedException;
import com.example.app.error.BusinessException;
import com.example.app.service.SettingsService.StoreSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.example.app.service.TestServices.CASHIER;
import static com.example.app.service.TestServices.CASHIER_PASSWORD;
import static com.example.app.service.TestServices.MANAGER;
import static com.example.app.service.TestServices.MANAGER_PASSWORD;
import static com.example.app.service.TestServices.OWNER;
import static com.example.app.service.TestServices.OWNER_PASSWORD;
import static com.example.app.service.TestServices.cashierOpensShift;
import static com.example.app.service.TestServices.product;
import static com.example.app.service.TestServices.sellCash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Store settings: who may change them, what is checked, and when the currency is locked. */
class SettingsRulesTest {

    private AppServices app;

    @BeforeEach
    void setUp() {
        app = TestServices.fresh();
    }

    @AfterEach
    void tearDown() {
        app.close();
        MoneyFormat.configure("USD", "en-US");
    }

    private static StoreSettings withCurrency(String currency, String locale) {
        return new StoreSettings("Corner Shop", currency, locale, 1000, false, "Come again");
    }

    @Test
    void theOwnerCanChangeTheCurrencyBeforeAnySaleAndTheNewFormatTakesEffect() {
        app.auth().login(OWNER, OWNER_PASSWORD);
        app.storeSettings().update(withCurrency("EUR", "de-DE"));

        assertEquals("EUR", app.storeSettings().current().currency());
        assertTrue(MoneyFormat.format(123_456).startsWith("1.234,56"), MoneyFormat.format(123_456));
    }

    @Test
    void theCurrencyIsLockedOnceASaleExists() {
        long coffee = product(app, "LCK", 500, 0, 10);
        cashierOpensShift(app, 0);
        sellCash(app, coffee, 1, 0, 500);

        app.auth().login(OWNER, OWNER_PASSWORD);
        BusinessException e = assertThrows(BusinessException.class,
                () -> app.storeSettings().update(withCurrency("EUR", "de-DE")));
        assertEquals("The currency cannot be changed after the first sale", e.getMessage());

        // Other settings can still change after the first sale.
        app.storeSettings().update(new StoreSettings("Renamed", "USD", "en-US", 1000, false, "Bye"));
        assertEquals("Renamed", app.storeSettings().current().storeName());
    }

    @Test
    void onlyAnAdministratorCanChangeSettings() {
        app.auth().login(MANAGER, MANAGER_PASSWORD);
        assertThrows(AccessDeniedException.class, () -> app.storeSettings().update(withCurrency("USD", "en-US")));

        app.auth().login(CASHIER, CASHIER_PASSWORD);
        assertThrows(AccessDeniedException.class, () -> app.storeSettings().update(withCurrency("USD", "en-US")));
    }

    @Test
    void theDiscountLevelMustBeAPercentage() {
        app.auth().login(OWNER, OWNER_PASSWORD);
        BusinessException e = assertThrows(BusinessException.class, () -> app.storeSettings()
                .update(new StoreSettings("Shop", "USD", "en-US", 12_000, false, "")));
        assertEquals("The discount approval level must be between 0% and 100%", e.getMessage());
    }

    @Test
    void theSettingsAreStillThereAfterReopeningTheDatabase() {
        String url = TestServices.url();
        try (AppServices first = new AppServices(url, new com.example.app.util.PasswordHasher(1_000))) {
            first.auth().completeSetup(OWNER, OWNER_PASSWORD);
            first.storeSettings().update(new StoreSettings("Reopened", "GBP", "en-GB", 500, true, "Cheers"));
        }
        try (AppServices second = new AppServices(url, new com.example.app.util.PasswordHasher(1_000))) {
            second.auth().login(OWNER, OWNER_PASSWORD);
            StoreSettings loaded = second.storeSettings().current();
            assertEquals("Reopened", loaded.storeName());
            assertEquals("GBP", loaded.currency());
            assertEquals(500, loaded.discountApprovalBp());
            assertTrue(loaded.allowNegativeStock());
        }
    }
}
