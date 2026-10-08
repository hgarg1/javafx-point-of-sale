package com.example.app.service;

import com.example.app.domain.Limits;
import com.example.app.domain.ProductDraft;
import com.example.app.domain.Role;
import com.example.app.error.ApprovalRequiredException;
import com.example.app.error.BusinessException;
import com.example.app.service.CheckoutService.CartItem;
import com.example.app.service.HoldService.HeldCart;
import com.example.app.service.HoldService.Recalled;
import com.example.app.util.PasswordHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.example.app.service.TestServices.CASHIER;
import static com.example.app.service.TestServices.CASHIER_PASSWORD;
import static com.example.app.service.TestServices.MANAGER;
import static com.example.app.service.TestServices.MANAGER_PASSWORD;
import static com.example.app.service.TestServices.OWNER;
import static com.example.app.service.TestServices.OWNER_PASSWORD;
import static com.example.app.service.TestServices.cashierOpensShift;
import static com.example.app.service.TestServices.managerId;
import static com.example.app.service.TestServices.product;
import static com.example.app.service.TestServices.sellCash;
import static com.example.app.service.TestServices.stockOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Held carts: they survive a restart, are priced again on recall, and flag anything that cannot be sold as it stands. */
class HoldRulesTest {

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
    void aHeldCartSurvivesLogoutAndARestart(@TempDir Path dir) {
        String url = TestServices.fileUrl(dir, "till");
        try (AppServices before = new AppServices(url, new PasswordHasher(1_000))) {
            before.auth().completeSetup(OWNER, OWNER_PASSWORD);
            TestServices.provision(before, MANAGER, MANAGER_PASSWORD, Role.MANAGER);
            TestServices.provision(before, CASHIER, CASHIER_PASSWORD, Role.CASHIER);
            long rug = product(before, "HLD", 500, 0, 10);
            before.auth().login(CASHIER, CASHIER_PASSWORD);
            before.holds().hold(List.of(new CartItem(rug, 2, 0)), "Table 4");
            before.auth().logout();
        }

        try (AppServices after = new AppServices(url, new PasswordHasher(1_000))) {
            after.auth().login(CASHIER, CASHIER_PASSWORD);
            List<HeldCart> held = after.holds().list();
            assertEquals(1, held.size());
            assertEquals("Table 4", held.get(0).label());
            assertEquals(1, held.get(0).lineCount());
            assertEquals(CASHIER, held.get(0).cashier());
            assertEquals(1_000, held.get(0).totalCents());
        }
    }

    @Test
    void recallPricesTheCartAgainAtTodaysPrice() {
        long rug = product(app, "PRC", 500, 0, 10);
        app.auth().login(CASHIER, CASHIER_PASSWORD);
        long holdId = app.holds().hold(List.of(new CartItem(rug, 2, 0)), "");

        app.auth().login(MANAGER, MANAGER_PASSWORD);
        app.inventory().updateProduct(rug, new ProductDraft("PRC", null, "Product PRC", null, null, 700, 250, 0, 2, true));

        app.auth().login(CASHIER, CASHIER_PASSWORD);
        assertEquals(1_400, app.holds().list().get(0).totalCents(), "the held total follows the new price");
        Recalled recalled = app.holds().recall(holdId, List.of(), null);
        assertEquals(List.of(new CartItem(rug, 2, 0)), recalled.items());
        assertTrue(recalled.problems().isEmpty());
        assertEquals(1_400, app.checkout().quote(recalled.items()).subtotalCents());
    }

    @Test
    void recallFlagsALineThatIsNoLongerInStock() {
        long rug = product(app, "SHO", 500, 0, 3);
        cashierOpensShift(app, 0);
        long holdId = app.holds().hold(List.of(new CartItem(rug, 2, 0)), "Walk-in");
        // Another sale takes two of the three, so only one is left when the held cart comes back.
        sellCash(app, rug, 2, 0, 1_000);

        Recalled recalled = app.holds().recall(holdId, List.of(), null);

        assertEquals(List.of("Only 1 of Product SHO in stock now"), recalled.problems());
        assertEquals(1, recalled.items().size(), "the line stays on the cart so the cashier can change it");
    }

    @Test
    void recallFlagsAProductThatHasBeenTakenOffSale() {
        long rug = product(app, "OFF", 500, 0, 5);
        app.auth().login(CASHIER, CASHIER_PASSWORD);
        long holdId = app.holds().hold(List.of(new CartItem(rug, 1, 0)), "");

        app.auth().login(MANAGER, MANAGER_PASSWORD);
        app.inventory().updateProduct(rug, new ProductDraft("OFF", null, "Product OFF", null, null, 500, 250, 0, 2, false));

        app.auth().login(CASHIER, CASHIER_PASSWORD);
        Recalled recalled = app.holds().recall(holdId, List.of(), null);
        assertEquals(List.of("Product OFF is no longer for sale; remove it before taking payment"), recalled.problems());
    }

    @Test
    void discardingAHoldRemovesItAndLeavesStockAlone() {
        long rug = product(app, "DIS", 500, 0, 4);
        app.auth().login(CASHIER, CASHIER_PASSWORD);
        long holdId = app.holds().hold(List.of(new CartItem(rug, 3, 0)), "Bin");

        app.holds().discard(holdId);

        assertTrue(app.holds().list().isEmpty());
        assertEquals(4, stockOf(app, rug));
    }

    @Test
    void aHeldCartCanOnlyBeRecalledOnce() {
        long rug = product(app, "ONE", 500, 0, 4);
        app.auth().login(CASHIER, CASHIER_PASSWORD);
        long holdId = app.holds().hold(List.of(new CartItem(rug, 1, 0)), "");
        app.holds().recall(holdId, List.of(), null);

        BusinessException e = assertThrows(BusinessException.class, () -> app.holds().recall(holdId, List.of(), null));
        assertEquals("That held sale no longer exists", e.getMessage());
    }

    @Test
    void anEmptyCartOrAnOverlongLabelIsRefused() {
        long rug = product(app, "BAD", 500, 0, 4);
        app.auth().login(CASHIER, CASHIER_PASSWORD);

        BusinessException empty = assertThrows(BusinessException.class, () -> app.holds().hold(List.of(), "x"));
        assertEquals("The cart is empty", empty.getMessage());

        String tooLong = "x".repeat(HoldService.MAX_LABEL + 1);
        BusinessException label = assertThrows(BusinessException.class,
                () -> app.holds().hold(List.of(new CartItem(rug, 1, 0)), tooLong));
        assertEquals("The label must be 60 characters or fewer", label.getMessage());
    }

    @Test
    void aHeldLineMergesIntoTheSameProductAtTheSameDiscount() {
        long rug = product(app, "MRG1", 500, 0, 10);
        app.auth().login(CASHIER, CASHIER_PASSWORD);
        long holdId = app.holds().hold(List.of(new CartItem(rug, 2, 0)), "Table 4");

        Recalled recalled = app.holds().recall(holdId, List.of(new CartItem(rug, 1, 0)), null);

        assertEquals(List.of(new CartItem(rug, 3, 0)), recalled.items());
        assertEquals(0, recalled.linesAdded());
        assertEquals(1, recalled.linesCombined());
        assertTrue(recalled.problems().isEmpty());
        assertTrue(app.holds().list().isEmpty(), "a merged hold is removed");
    }

    @Test
    void aHeldLineAtADifferentDiscountJoinsTheLineAndTakesTheHigherDiscount() {
        long rug = product(app, "MRG2", 500, 0, 10);
        app.auth().login(CASHIER, CASHIER_PASSWORD);
        long holdId = app.holds().hold(List.of(new CartItem(rug, 2, 500)), "");

        Recalled recalled = app.holds().recall(holdId, List.of(new CartItem(rug, 1, 0)), null);

        assertEquals(List.of(new CartItem(rug, 3, 500)), recalled.items());
        assertEquals(0, recalled.linesAdded());
        assertEquals(1, recalled.linesCombined());
    }

    @Test
    void aHeldLineAtALowerDiscountKeepsTheHigherDiscountOnTheCartLine() {
        long rug = product(app, "MRG12", 500, 0, 10);
        app.auth().login(CASHIER, CASHIER_PASSWORD);
        long holdId = app.holds().hold(List.of(new CartItem(rug, 2, 0)), "");

        Recalled recalled = app.holds().recall(holdId, List.of(new CartItem(rug, 1, 500)), null);

        assertEquals(List.of(new CartItem(rug, 3, 500)), recalled.items());
        assertEquals(1, recalled.linesCombined());
    }

    @Test
    void aHeldLineJoinsTheCartLineThatAlreadyHasTheHighestDiscount() {
        long rug = product(app, "MRG13", 500, 0, 10);
        app.auth().login(CASHIER, CASHIER_PASSWORD);
        long holdId = app.holds().hold(List.of(new CartItem(rug, 1, 500)), "");

        Recalled recalled = app.holds().recall(holdId,
                List.of(new CartItem(rug, 1, 0), new CartItem(rug, 1, 800)), null);

        assertEquals(List.of(new CartItem(rug, 1, 0), new CartItem(rug, 2, 800)), recalled.items());
        assertEquals(1, recalled.linesCombined());
    }

    @Test
    void aCombinedLineAboveTheApprovalLevelNeedsAManager() {
        long rug = product(app, "MRG14", 500, 0, 10);
        app.auth().login(CASHIER, CASHIER_PASSWORD);
        long holdId = app.holds().hold(List.of(new CartItem(rug, 1, 2_000)), "Regular");

        assertThrows(ApprovalRequiredException.class,
                () -> app.holds().recall(holdId, List.of(new CartItem(rug, 1, 500)), null));
        assertEquals(1, app.holds().list().size(), "a refused recall keeps the held sale");

        Recalled recalled = app.holds().recall(holdId, List.of(new CartItem(rug, 1, 500)), managerId(app));
        assertEquals(List.of(new CartItem(rug, 2, 2_000)), recalled.items());
        assertTrue(app.holds().list().isEmpty());
    }

    @Test
    void aComplexMergeCombinesWhatMatchesAndAddsTheRest() {
        long tea = product(app, "MRG3", 300, 0, 10);
        long rice = product(app, "MRG4", 200, 0, 10);
        long soap = product(app, "MRG5", 400, 0, 10);
        app.auth().login(CASHIER, CASHIER_PASSWORD);
        long holdId = app.holds().hold(List.of(
                new CartItem(rice, 1, 500),
                new CartItem(tea, 2, 1_000),
                new CartItem(soap, 1, 0)), "Booth 2");

        Recalled recalled = app.holds().recall(holdId,
                List.of(new CartItem(tea, 1, 0), new CartItem(rice, 2, 500)), null);

        assertEquals(List.of(new CartItem(tea, 3, 1_000), new CartItem(rice, 3, 500),
                new CartItem(soap, 1, 0)), recalled.items());
        assertEquals(1, recalled.linesAdded());
        assertEquals(2, recalled.linesCombined());
        assertTrue(recalled.problems().isEmpty());
    }

    @Test
    void mergedQuantitiesAreCheckedAgainstStock() {
        long rug = product(app, "MRG6", 500, 0, 3);
        app.auth().login(CASHIER, CASHIER_PASSWORD);
        long holdId = app.holds().hold(List.of(new CartItem(rug, 2, 0)), "");

        Recalled recalled = app.holds().recall(holdId, List.of(new CartItem(rug, 2, 0)), null);

        assertEquals(List.of(new CartItem(rug, 4, 0)), recalled.items());
        assertEquals(List.of("Only 3 of Product MRG6 in stock now"), recalled.problems());
    }

    @Test
    void aDiscountAboveTheApprovalLevelNeedsAManagerAndARefusedRecallKeepsTheHold() {
        long rug = product(app, "MRG7", 500, 0, 10);
        app.auth().login(CASHIER, CASHIER_PASSWORD);
        long holdId = app.holds().hold(List.of(new CartItem(rug, 1, 2_000)), "Regular");

        assertThrows(ApprovalRequiredException.class, () -> app.holds().recall(holdId, List.of(), null));
        assertEquals(1, app.holds().list().size(), "a refused recall keeps the held sale");

        Recalled recalled = app.holds().recall(holdId, List.of(), managerId(app));
        assertEquals(List.of(new CartItem(rug, 1, 2_000)), recalled.items());
        assertTrue(app.holds().list().isEmpty());
    }

    @Test
    void aMergeOverTheLineLimitIsRefusedAndKeepsTheHold() {
        app.auth().login(MANAGER, MANAGER_PASSWORD);
        List<CartItem> full = new ArrayList<>();
        for (int i = 0; i < CheckoutService.MAX_LINES; i++) {
            long id = app.inventory().createProduct(
                    new ProductDraft("LIM" + i, null, "Limit " + i, null, null, 100, 50, 0, 0, true), 5);
            full.add(new CartItem(id, 1, 0));
        }
        long extra = app.inventory().createProduct(
                new ProductDraft("LIMX", null, "Extra", null, null, 100, 50, 0, 0, true), 5);
        app.auth().login(CASHIER, CASHIER_PASSWORD);
        long holdId = app.holds().hold(List.of(new CartItem(extra, 1, 0)), "Full cart");

        BusinessException e = assertThrows(BusinessException.class, () -> app.holds().recall(holdId, full, null));

        assertTrue(e.getMessage().startsWith("The sale would have " + (CheckoutService.MAX_LINES + 1) + " lines"),
                e.getMessage());
        assertEquals(1, app.holds().list().size(), "the held sale is kept");
    }

    @Test
    void aMergeThatWouldPassTheQuantityLimitIsRefusedAndKeepsTheHold() {
        long rug = product(app, "MRG8", 500, 0, Limits.MAX_QUANTITY);
        app.auth().login(CASHIER, CASHIER_PASSWORD);
        long holdId = app.holds().hold(List.of(new CartItem(rug, 60_000, 0)), "Bulk");

        BusinessException e = assertThrows(BusinessException.class,
                () -> app.holds().recall(holdId, List.of(new CartItem(rug, 60_000, 0)), null));

        assertTrue(e.getMessage().startsWith("Product MRG8 would have 120000 on one line"), e.getMessage());
        assertEquals(1, app.holds().list().size(), "the held sale is kept");
    }
}
