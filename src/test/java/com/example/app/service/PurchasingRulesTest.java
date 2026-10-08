package com.example.app.service;

import com.example.app.domain.MovementType;
import com.example.app.domain.ProductDraft;
import com.example.app.domain.ProductKind;
import com.example.app.domain.PurchaseStatus;
import com.example.app.domain.StockMovement;
import com.example.app.error.AccessDeniedException;
import com.example.app.error.BusinessException;
import com.example.app.service.PurchasingService.ReceiveLine;
import com.example.app.service.PurchasingService.Suggestion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.example.app.service.TestServices.CASHIER;
import static com.example.app.service.TestServices.CASHIER_PASSWORD;
import static com.example.app.service.TestServices.MANAGER;
import static com.example.app.service.TestServices.MANAGER_PASSWORD;
import static com.example.app.service.TestServices.product;
import static com.example.app.service.TestServices.productNamed;
import static com.example.app.service.TestServices.stockOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Purchase orders: the path from draft to received, partial deliveries, average cost, and who may do what. */
class PurchasingRulesTest {

    private AppServices app;
    private int suppliersMade;

    @BeforeEach
    void setUp() {
        app = TestServices.fresh();
    }

    @AfterEach
    void tearDown() {
        app.close();
    }

    @Test
    void aPartialDeliveryLeavesTheOrderPartiallyReceivedUntilTheRestArrives() {
        long rug = product(app, "PO1", 900, 0, 0);
        long order = app.purchasing().createDraft(newSupplier(), "Monthly restock");
        long line = app.purchasing().addLine(order, rug, 10, 300);
        app.purchasing().order(order);

        app.purchasing().receive(order, "INV-1", List.of(new ReceiveLine(line, 4, 300)));
        assertEquals(PurchaseStatus.PARTIAL, status(order));
        assertEquals(4, stockOf(app, rug));

        app.purchasing().receive(order, "INV-2", List.of(new ReceiveLine(line, 6, 300)));
        assertEquals(PurchaseStatus.RECEIVED, status(order));
        assertEquals(10, stockOf(app, rug));
    }

    @Test
    void aDeliveryCannotBeMoreThanWasOrdered() {
        long rug = product(app, "PO2", 900, 0, 0);
        long order = ordered(rug, 3, 300);

        BusinessException e = assertThrows(BusinessException.class,
                () -> app.purchasing().receive(order, null, List.of(new ReceiveLine(lineOf(order), 4, 300))));
        assertEquals("Only 3 left to receive for Product PO2", e.getMessage());
        assertEquals(0, stockOf(app, rug), "a refused delivery adds no stock");
    }

    @Test
    void theAverageCostFollowsEachDelivery() {
        long rug = product(app, "AVG", 900, 0, 4);   // cost 450
        long order = ordered(rug, 6, 600);

        app.purchasing().receive(order, null, List.of(new ReceiveLine(lineOf(order), 6, 600)));

        // (4 on hand at 450 + 6 arriving at 600) / 10 = 540
        assertEquals(540, productNamed(app, rug).costCents());
        assertEquals(10, stockOf(app, rug));
    }

    @Test
    void aDeliveryIntoEmptyOrNegativeStockTakesTheDeliveredCost() {
        long empty = product(app, "EMP", 900, 0, 0);   // cost 450
        long emptyOrder = ordered(empty, 5, 700);
        app.purchasing().receive(emptyOrder, null, List.of(new ReceiveLine(lineOf(emptyOrder), 5, 700)));
        assertEquals(700, productNamed(app, empty).costCents());

        long negative = product(app, "NEG", 900, 0, 0);
        app.inventory().recordMovement(negative, MovementType.ADJUST, -2, "Miscount");
        long negativeOrder = ordered(negative, 3, 800);
        app.purchasing().receive(negativeOrder, null, List.of(new ReceiveLine(lineOf(negativeOrder), 3, 800)));
        assertEquals(800, productNamed(app, negative).costCents());
        assertEquals(1, stockOf(app, negative));
    }

    @Test
    void aDeliveryMovementPointsAtItsReceipt() {
        long rug = product(app, "TRC", 900, 0, 0);
        long order = ordered(rug, 2, 300);

        long receipt = app.purchasing().receive(order, "INV-9", List.of(new ReceiveLine(lineOf(order), 2, 300)));

        List<StockMovement> movements = app.inventory().movementsFor(rug);
        assertTrue(movements.stream().anyMatch(m -> m.type() == MovementType.RECEIVE
                && Long.valueOf(receipt).equals(m.refReceiptId())));
    }

    @Test
    void cashiersCannotRaiseOrReceiveOrders() {
        product(app, "ROL", 900, 0, 0);
        long supplier = newSupplier();
        app.auth().login(CASHIER, CASHIER_PASSWORD);

        assertThrows(AccessDeniedException.class, () -> app.purchasing().createDraft(supplier, null));
        assertThrows(AccessDeniedException.class, () -> app.purchasing().list(null));
    }

    @Test
    void aDraftCanBeCancelledButAnOrderWithArrivedGoodsCannot() {
        long rug = product(app, "CAN", 900, 0, 0);
        long draft = app.purchasing().createDraft(newSupplier(), null);
        app.purchasing().cancel(draft);
        assertEquals(PurchaseStatus.CANCELLED, status(draft));

        long order = ordered(rug, 5, 300);
        app.purchasing().receive(order, null, List.of(new ReceiveLine(lineOf(order), 1, 300)));
        BusinessException e = assertThrows(BusinessException.class, () -> app.purchasing().cancel(order));
        assertEquals("Goods have already arrived on this order, so it cannot be cancelled", e.getMessage());
    }

    @Test
    void onlyADraftCanChangeAndAnEmptyOrderCannotBeSent() {
        long rug = product(app, "DRF", 900, 0, 0);
        long other = product(app, "DRF2", 900, 0, 0);
        long order = app.purchasing().createDraft(newSupplier(), null);

        BusinessException empty = assertThrows(BusinessException.class, () -> app.purchasing().order(order));
        assertEquals("Add at least one item before ordering", empty.getMessage());

        app.purchasing().addLine(order, rug, 2, 300);
        app.purchasing().order(order);
        BusinessException late = assertThrows(BusinessException.class,
                () -> app.purchasing().addLine(order, other, 1, 300));
        assertEquals("Only a draft can be changed", late.getMessage());
    }

    @Test
    void giftCardsAndRepeatedLinesAreRefused() {
        long rug = product(app, "DUP", 900, 0, 0);
        long card = app.inventory().createProduct(new ProductDraft("GC9", null, "Gift card", null, null, 2_500, 0, 0, 0,
                true, ProductKind.GIFT_CARD), 0);
        long order = app.purchasing().createDraft(newSupplier(), null);

        BusinessException giftCard = assertThrows(BusinessException.class,
                () -> app.purchasing().addLine(order, card, 1, 0));
        assertEquals("Gift cards are not stocked, so they cannot be ordered", giftCard.getMessage());

        app.purchasing().addLine(order, rug, 1, 300);
        BusinessException repeat = assertThrows(BusinessException.class,
                () -> app.purchasing().addLine(order, rug, 2, 300));
        assertEquals("Product DUP is already on this order. Remove that line first.", repeat.getMessage());
    }

    @Test
    void reorderSuggestionsUseTheOrderQuantityOrTwiceTheReorderLevel() {
        app.auth().login(MANAGER, MANAGER_PASSWORD);
        long supplier = newSupplier();
        app.inventory().createProduct(new ProductDraft("LOW", null, "Product LOW", null, supplier, 900, 450, 0, 5, true), 2);
        app.inventory().createProduct(new ProductDraft("SET", null, "Product SET", null, supplier, 900, 450, 0, 5, 12,
                true, ProductKind.STOCK), 2);
        app.inventory().createProduct(new ProductDraft("FINE", null, "Product FINE", null, supplier, 900, 450, 0, 5, true), 20);

        List<Suggestion> suggestions = app.purchasing().reorderSuggestions();

        assertEquals(List.of("LOW", "SET"), suggestions.stream().map(Suggestion::sku).toList());
        assertEquals(8, suggestions.get(0).suggestedQty(), "twice the reorder level of 5, less the 2 on hand");
        assertEquals(12, suggestions.get(1).suggestedQty(), "the product's own order quantity");
    }

    @Test
    void draftOrdersFromSuggestionsGroupEachSupplierAndSkipTheProductsWithNone() {
        app.auth().login(MANAGER, MANAGER_PASSWORD);
        long first = newSupplier();
        long second = newSupplier();
        app.inventory().createProduct(new ProductDraft("A1", null, "Product A1", null, first, 900, 450, 0, 5, true), 1);
        app.inventory().createProduct(new ProductDraft("A2", null, "Product A2", null, first, 900, 450, 0, 5, true), 1);
        app.inventory().createProduct(new ProductDraft("B1", null, "Product B1", null, second, 900, 450, 0, 5, true), 1);
        app.inventory().createProduct(new ProductDraft("LOOSE", null, "Product LOOSE", null, null, 900, 450, 0, 5, true), 1);

        List<Long> drafts = app.purchasing().draftFromSuggestions();

        assertEquals(2, drafts.size(), "one draft per supplier, and the product with no supplier is skipped");
        assertEquals(PurchaseStatus.DRAFT, status(drafts.get(0)));
        assertEquals(2, app.purchasing().detail(drafts.get(0)).lines().size());
        assertEquals(1, app.purchasing().detail(drafts.get(1)).lines().size());

        // The supplier-less product is still low, but it cannot be drafted, and the others are already on drafts.
        assertEquals(List.of("LOOSE"), app.purchasing().reorderSuggestions().stream().map(Suggestion::sku).toList());
        assertTrue(app.purchasing().draftFromSuggestions().isEmpty(), "drafting again must not duplicate anything");
    }

    @Test
    void anOrderExportsItsLinesToCsv(@TempDir Path dir) throws Exception {
        long rug = product(app, "CSV", 900, 0, 0);
        long order = ordered(rug, 2, 300);
        Path file = dir.resolve("order.csv");

        app.purchasing().exportCsv(order, file);

        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.contains("qty_ordered"));
        assertTrue(text.contains("Product CSV"));
        assertTrue(text.contains("6.00"), "2 at 3.00 is a line total of 6.00");
    }

    /** A new supplier with a name nobody else has. The manager must be logged in. */
    private long newSupplier() {
        return app.inventory().addSupplier("Supplier " + (++suppliersMade), null);
    }

    /** A sent order for one product, to a new supplier. The manager must be logged in. */
    private long ordered(long productId, int qty, long unitCostCents) {
        long order = app.purchasing().createDraft(newSupplier(), null);
        app.purchasing().addLine(order, productId, qty, unitCostCents);
        app.purchasing().order(order);
        return order;
    }

    private long lineOf(long order) {
        return app.purchasing().detail(order).lines().get(0).id();
    }

    private PurchaseStatus status(long order) {
        return app.purchasing().detail(order).summary().status();
    }
}
