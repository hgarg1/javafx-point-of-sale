package com.example.app.service;

import com.example.app.domain.Limits;
import com.example.app.domain.MovementType;
import com.example.app.domain.ProductDraft;
import com.example.app.error.BusinessException;
import com.example.app.repository.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.example.app.service.TestServices.MANAGER;
import static com.example.app.service.TestServices.MANAGER_PASSWORD;
import static com.example.app.service.TestServices.product;
import static com.example.app.service.TestServices.stockOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Catalogue and stock rules: codes, duplicates, limits, search and the movement history. */
class InventoryRulesTest {

    private AppServices app;

    @BeforeEach
    void setUp() {
        app = TestServices.fresh();
        app.auth().login(MANAGER, MANAGER_PASSWORD);
    }

    @AfterEach
    void tearDown() {
        app.close();
    }

    @Test
    void skusAreStoredInUpperCaseAndFoundIgnoringCase() {
        long id = app.inventory().createProduct(new ProductDraft("abc-1", null, "Bun", null, null, 100, 50, 0, 0, true), 0);

        assertEquals("ABC-1", app.inventory().findProduct(id).orElseThrow().sku());
        assertTrue(app.inventory().findByCode("abc-1").isPresent());
    }

    @Test
    void aDuplicateSkuNamesTheProductItClashesWith() {
        product(app, "DUP", 100, 0, 0);
        BusinessException e = assertThrows(BusinessException.class, () -> app.inventory()
                .createProduct(new ProductDraft("dup", null, "Other", null, null, 100, 50, 0, 0, true), 0));
        assertEquals("SKU DUP is already used by Product DUP", e.getMessage());
    }

    @Test
    void aDuplicateCategoryGivesAFriendlyMessageNotADatabaseError() {
        app.inventory().addCategory("Bakery");

        BusinessException e = assertThrows(BusinessException.class, () -> app.inventory().addCategory("bakery"));
        assertEquals("A category named 'bakery' already exists", e.getMessage());
    }

    @Test
    void amountsAboveTheLimitAreRejected() {
        BusinessException e = assertThrows(BusinessException.class, () -> app.inventory().createProduct(
                new ProductDraft("BIG", null, "Big", null, null, Limits.MAX_AMOUNT_CENTS + 1, 0, 0, 0, true), 0));
        assertTrue(e.getMessage().startsWith("Price must be between $0.00 and "), e.getMessage());
    }

    @Test
    void searchTreatsTypedWildcardsLiterally() {
        app.inventory().createProduct(new ProductDraft("PCT", null, "100% Pure", null, null, 100, 50, 0, 0, true), 0);
        app.inventory().createProduct(new ProductDraft("PLN", null, "Plain", null, null, 100, 50, 0, 0, true), 0);

        assertEquals(1, app.inventory().search("%").size());
        assertEquals("100% Pure", app.inventory().search("%").get(0).name());
        assertEquals(0, app.inventory().search("_").size());
        assertEquals(2, app.inventory().search("").size());
    }

    @Test
    void searchReturnsAtMostFiftyResults() {
        for (int i = 1; i <= 60; i++) {
            app.inventory().createProduct(new ProductDraft("ITM" + i, null, String.format("Item %03d", i),
                    null, null, 100, 50, 0, 0, true), 0);
        }
        assertEquals(ProductRepository.SEARCH_LIMIT, app.inventory().search("item").size());
    }

    @Test
    void wasteNeedsAReasonAndIsStoredAsStockOut() {
        long mug = product(app, "WST", 300, 0, 10);

        assertThrows(BusinessException.class,
                () -> app.inventory().recordMovement(mug, MovementType.WASTE, 1, ""));
        app.inventory().recordMovement(mug, MovementType.WASTE, 2, "broken in transit");

        assertEquals(8, stockOf(app, mug));
        assertEquals(-2, app.inventory().movementsFor(mug).get(0).qtyDelta());
    }

    @Test
    void stockTakeAdjustsOnlyChangedProductsAndMovementsStayConsistent() {
        long mug = product(app, "MUG", 300, 0, 10);

        assertEquals(1, app.inventory().stockTake(Map.of(mug, 7)));

        assertEquals(7, stockOf(app, mug));
        int movementTotal = app.inventory().movementsFor(mug).stream().mapToInt(m -> m.qtyDelta()).sum();
        assertEquals(7, movementTotal, "stock on hand must equal the sum of its movements");
    }

    @Test
    void stockTakeRejectsNegativeCounts() {
        long mug = product(app, "NEG", 300, 0, 10);
        assertThrows(BusinessException.class, () -> app.inventory().stockTake(Map.of(mug, -1)));
        assertEquals(10, stockOf(app, mug));
    }

    @Test
    void priceChangesAreRecordedInTheAuditLog() {
        long mug = product(app, "AUD", 300, 0, 10);
        app.inventory().updateProduct(mug, new ProductDraft("AUD", null, "Product AUD", null, null, 600, 150, 0, 2, true));

        boolean logged = app.audit().recent(50).stream()
                .anyMatch(e -> "PRICE_CHANGE".equals(e.action()) && "3.00 -> 6.00".equals(e.detail()));
        assertTrue(logged);
    }

    @Test
    void anInactiveProductIsKeptButNotOnTheLowStockOrSaleLists() {
        long mug = product(app, "OFF", 300, 0, 0);
        app.inventory().updateProduct(mug, new ProductDraft("OFF", null, "Product OFF", null, null, 300, 150, 0, 2, false));

        assertFalse(app.inventory().findProduct(mug).orElseThrow().active());
        assertEquals(0, app.inventory().listLowStock().size());
    }

    @Test
    void theMovementHistoryRecordsWhoMadeEachChange() {
        long mug = product(app, "WHO", 300, 0, 4);
        app.inventory().recordMovement(mug, MovementType.ADJUST, -1, "recount");

        assertEquals(2, app.inventory().movementsFor(mug).size());
        assertTrue(app.inventory().movementsFor(mug).stream().allMatch(m -> m.userId() > 0));
    }
}
