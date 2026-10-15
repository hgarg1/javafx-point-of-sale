package com.example.app.service;

import com.example.app.domain.ProductDraft;
import com.example.app.domain.ProductKind;
import com.example.app.error.BusinessException;
import com.example.app.service.CheckoutService.CartItem;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AvailabilityTest {
    @Test
    void availabilityChecksAggregateStockWithoutChangingPricingOrInventory() {
        try (AppServices app = TestServices.fresh()) {
            long id = TestServices.product(app, "STOCK", 500, 0, 3);
            TestServices.cashierOpensShift(app, 0);
            var cart = List.of(new CartItem(id, 2, 0), new CartItem(id, 2, 1000));
            assertDoesNotThrow(() -> app.checkout().quote(cart));
            assertThrows(BusinessException.class, () -> app.checkout().validateAvailability(cart));
            assertDoesNotThrow(() -> app.checkout().validateAvailability(List.of(new CartItem(id, 3, 0))));
            assertEquals(3, TestServices.stockOf(app, id));
        }
    }

    @Test
    void inactiveGiftCardsAndNegativeStockFollowStoreRules() {
        try (AppServices app = TestServices.fresh()) {
            app.auth().login(TestServices.OWNER, TestServices.OWNER_PASSWORD);
            long inactive = app.inventory().createProduct(new ProductDraft("OFF", null, "Inactive", null,
                    null, 500, 0, 0, 0, false), 5);
            long gift = app.inventory().createProduct(new ProductDraft("GIFT", null, "Gift", null,
                    null, 500, 0, 0, 0, true, ProductKind.GIFT_CARD), 0);
            long stock = app.inventory().createProduct(new ProductDraft("EMPTY", null, "Empty", null,
                    null, 500, 0, 0, 0, true), 0);
            assertThrows(BusinessException.class,
                    () -> app.checkout().validateAvailability(List.of(new CartItem(inactive, 1, 0))));
            assertDoesNotThrow(() -> app.checkout().validateAvailability(List.of(new CartItem(gift, 2, 0))));
            assertThrows(BusinessException.class,
                    () -> app.checkout().validateAvailability(List.of(new CartItem(stock, 1, 0))));
            var current = app.storeSettings().current();
            app.storeSettings().update(new SettingsService.StoreSettings(current.storeName(), current.currency(),
                    current.locale(), current.discountApprovalBp(), true, current.receiptFooter()));
            assertDoesNotThrow(() -> app.checkout().validateAvailability(List.of(new CartItem(stock, 1, 0))));
            assertThrows(BusinessException.class,
                    () -> app.checkout().validateAvailability(List.of(new CartItem(inactive, 1, 0))));
        }
    }
}
