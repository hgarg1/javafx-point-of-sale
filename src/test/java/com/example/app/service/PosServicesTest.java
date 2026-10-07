package com.example.app.service;

import com.example.app.domain.MovementType;
import com.example.app.domain.PaymentMethod;
import com.example.app.domain.Product;
import com.example.app.domain.ProductDraft;
import com.example.app.domain.Role;
import com.example.app.domain.Sale;
import com.example.app.domain.User;
import com.example.app.error.AccessDeniedException;
import com.example.app.error.ApprovalRequiredException;
import com.example.app.error.BusinessException;
import com.example.app.payment.PaymentDeclinedException;
import com.example.app.service.CheckoutService.CartItem;
import com.example.app.service.CheckoutService.PaymentRequest;
import com.example.app.service.CheckoutService.SaleDetails;
import com.example.app.service.RefundService.RefundLineRequest;
import com.example.app.service.ReportService.LedgerEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end checks of the business rules, running against an in-memory H2 database. */
class PosServicesTest {

    private static final String MANAGER = "manager1";
    private static final String MANAGER_PASSWORD = "manager-pass-1";
    private static final String CASHIER = "cashier1";
    private static final String CASHIER_PASSWORD = "cashier-pass-1";

    private AppServices app;

    @BeforeEach
    void setUp() {
        app = new AppServices("jdbc:h2:mem:" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1");
        app.auth().login("admin", "admin");
        app.auth().createUser(MANAGER, MANAGER_PASSWORD, Role.MANAGER);
        app.auth().createUser(CASHIER, CASHIER_PASSWORD, Role.CASHIER);
        app.auth().logout();
    }

    @AfterEach
    void tearDown() {
        app.close();
    }

    // ---- helpers ----

    private long createProduct(String sku, long priceCents, int taxBp, int stock) {
        app.auth().login(MANAGER, MANAGER_PASSWORD);
        ProductDraft draft = new ProductDraft(sku, null, "Product " + sku, null, null,
                priceCents, priceCents / 2, taxBp, 2, true);
        return app.inventory().createProduct(draft, stock);
    }

    private int stockOf(long productId) {
        return app.inventory().findProduct(productId).orElseThrow().stockQty();
    }

    private void loginCashierWithShift(long floatCents) {
        app.auth().login(CASHIER, CASHIER_PASSWORD);
        app.shifts().openShift(floatCents);
    }

    private SaleDetails sellCash(long productId, int qty, int discountBp, long tenderedCents) {
        return app.checkout().checkout(
                List.of(new CartItem(productId, qty, discountBp)),
                List.of(new PaymentRequest(PaymentMethod.CASH, tenderedCents, null)),
                null);
    }

    private long managerId() {
        return app.auth().verifyApprover(MANAGER, MANAGER_PASSWORD).id();
    }

    private void loginManagerWithShift() {
        app.auth().login(MANAGER, MANAGER_PASSWORD);
        app.shifts().openShift(0);
    }

    // ---- authentication ----

    @Test
    void firstAdminMustChangeThePasswordSetAtBootstrap() {
        app.auth().logout();
        User admin = app.auth().login("admin", "admin");
        assertTrue(admin.mustChangePassword());

        User updated = app.auth().changeOwnPassword("admin", "new-admin-pass");
        assertFalse(updated.mustChangePassword());
        app.auth().logout();

        assertThrows(BusinessException.class, () -> app.auth().login("admin", "admin"));
        assertEquals("admin", app.auth().login("admin", "new-admin-pass").username());
    }

    @Test
    void wrongPasswordGivesOneGenericMessage() {
        app.auth().logout();
        BusinessException e = assertThrows(BusinessException.class, () -> app.auth().login("admin", "nope"));
        assertEquals("Invalid username or password", e.getMessage());
    }

    @Test
    void reopeningTheDatabaseDoesNotCreateAnotherAdmin() {
        String url = "jdbc:h2:mem:reopen" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        try (AppServices first = new AppServices(url)) {
            first.auth().login("admin", "admin");
        }
        try (AppServices second = new AppServices(url)) {
            second.auth().login("admin", "admin");
            assertEquals(1, second.auth().listUsers().size());
        }
    }

    // ---- permissions ----

    @Test
    void cashierCannotManageProductsUsersOrReports() {
        app.auth().login(CASHIER, CASHIER_PASSWORD);
        ProductDraft draft = new ProductDraft("X", null, "X", null, null, 100, 50, 0, 0, true);
        assertThrows(AccessDeniedException.class, () -> app.inventory().createProduct(draft, 0));
        assertThrows(AccessDeniedException.class, () -> app.auth().listUsers());
        assertThrows(AccessDeniedException.class, () -> app.reports().ledger(LocalDate.now(), LocalDate.now()));
    }

    @Test
    void managerCannotCreateUsers() {
        app.auth().login(MANAGER, MANAGER_PASSWORD);
        assertThrows(AccessDeniedException.class,
                () -> app.auth().createUser("sneaky", "sneaky-pass-1", Role.ADMIN));
    }

    @Test
    void discountApproverMustBeAManager() {
        assertThrows(BusinessException.class, () -> app.auth().verifyApprover(CASHIER, CASHIER_PASSWORD));
        assertEquals(MANAGER, app.auth().verifyApprover(MANAGER, MANAGER_PASSWORD).username());
    }

    // ---- checkout ----

    @Test
    void cashSaleAddsTaxReturnsChangeAndDecrementsStock() {
        long coffee = createProduct("COF", 500, 800, 10);
        loginCashierWithShift(10_000);

        SaleDetails details = sellCash(coffee, 2, 0, 1_200);

        assertEquals(1_000, details.sale().subtotalCents());
        assertEquals(80, details.sale().taxCents());
        assertEquals(1_080, details.sale().totalCents());
        assertEquals(1_080, details.payments().get(0).appliedCents());
        assertEquals(120, details.payments().get(0).changeCents());
        assertEquals(8, stockOf(coffee));
    }

    @Test
    void quoteMatchesTheTotalThatIsCharged() {
        long coffee = createProduct("QUO", 333, 825, 10);
        loginCashierWithShift(0);
        List<CartItem> cart = List.of(new CartItem(coffee, 3, 1000));

        long quoted = app.checkout().quote(cart).totalCents();
        SaleDetails details = app.checkout().checkout(cart,
                List.of(new PaymentRequest(PaymentMethod.CASH, quoted, null)), null);

        assertEquals(quoted, details.sale().totalCents());
    }

    @Test
    void declinedCardWritesNothingAndKeepsStock() {
        long coffee = createProduct("DEC", 500, 0, 10);
        loginCashierWithShift(0);

        assertThrows(PaymentDeclinedException.class, () -> app.checkout().checkout(
                List.of(new CartItem(coffee, 1, 0)),
                List.of(new PaymentRequest(PaymentMethod.CARD, 500, "4111 1111 1111 0002")),
                null));

        assertEquals(0, app.shifts().currentSummary().salesCount());
        assertEquals(10, stockOf(coffee));
    }

    @Test
    void approvedCardSaleIsRecordedWithAProviderReference() {
        long coffee = createProduct("CRD", 500, 0, 10);
        loginCashierWithShift(0);

        SaleDetails details = app.checkout().checkout(
                List.of(new CartItem(coffee, 1, 0)),
                List.of(new PaymentRequest(PaymentMethod.CARD, 500, "4111111111111111")),
                null);

        assertEquals("SIM-", details.payments().get(0).providerRef().substring(0, 4));
        assertEquals(0, details.payments().get(0).changeCents());
    }

    @Test
    void shortPaymentIsRejected() {
        long coffee = createProduct("SHT", 500, 0, 10);
        loginCashierWithShift(0);

        BusinessException e = assertThrows(BusinessException.class, () -> sellCash(coffee, 2, 0, 900));
        assertEquals("Payment is short by $1.00", e.getMessage());
    }

    @Test
    void sellingMoreThanInStockIsRejected() {
        long coffee = createProduct("OUT", 500, 0, 10);
        loginCashierWithShift(0);

        BusinessException e = assertThrows(BusinessException.class, () -> sellCash(coffee, 11, 0, 99_999));
        assertEquals("Only 10 of Product OUT in stock", e.getMessage());
    }

    @Test
    void duplicateSkuIsRejected() {
        createProduct("DUP", 100, 0, 0);
        app.auth().login(MANAGER, MANAGER_PASSWORD);
        ProductDraft again = new ProductDraft("DUP", null, "Another", null, null, 100, 50, 0, 0, true);
        BusinessException e = assertThrows(BusinessException.class,
                () -> app.inventory().createProduct(again, 0));
        assertTrue(e.getMessage().contains("already used"));
    }

    @Test
    void largeDiscountNeedsAManagerApproval() {
        long coffee = createProduct("DSC", 500, 0, 10);
        loginCashierWithShift(0);
        List<CartItem> cart = List.of(new CartItem(coffee, 1, 2_000));
        List<PaymentRequest> pay = List.of(new PaymentRequest(PaymentMethod.CASH, 400, null));

        assertThrows(ApprovalRequiredException.class, () -> app.checkout().checkout(cart, pay, null));
        assertEquals(10, stockOf(coffee), "a rejected checkout must not touch stock");

        SaleDetails details = app.checkout().checkout(cart, pay, managerId());
        assertEquals(managerId(), details.sale().discountApprovedBy());
        assertEquals(400, details.sale().totalCents());
    }

    @Test
    void saleLinesKeepTheNameAndPriceFromTheDayOfSale() {
        long coffee = createProduct("SNP", 500, 0, 10);
        loginCashierWithShift(0);
        SaleDetails details = sellCash(coffee, 1, 0, 500);

        app.auth().login(MANAGER, MANAGER_PASSWORD);
        app.inventory().updateProduct(coffee,
                new ProductDraft("SNP", null, "Renamed SNP", null, null, 900, 250, 0, 2, true));

        var line = app.refunds().refundableLines(details.sale().id()).get(0).line();
        assertEquals(500, line.unitPriceCents());
        assertEquals("Product SNP", line.nameSnapshot());
    }

    // ---- refunds ----

    @Test
    void partialRefundsNeverLoseACent() {
        // 100 each, 3 for 200 after a 33.33% discount, so the line total does not divide by 3.
        long rug = createProduct("RUG", 100, 0, 10);
        loginCashierWithShift(0);
        Sale sale = app.checkout().checkout(
                List.of(new CartItem(rug, 3, 3_333)),
                List.of(new PaymentRequest(PaymentMethod.CASH, 200, null)),
                managerId()).sale();
        assertEquals(200, sale.totalCents());

        loginManagerWithShift();
        long lineId = app.refunds().refundableLines(sale.id()).get(0).line().id();
        app.refunds().refund(sale.id(), List.of(new RefundLineRequest(lineId, 1)), PaymentMethod.CASH, "returned", false);
        app.refunds().refund(sale.id(), List.of(new RefundLineRequest(lineId, 1)), PaymentMethod.CASH, "returned", false);
        app.refunds().refund(sale.id(), List.of(new RefundLineRequest(lineId, 1)), PaymentMethod.CASH, "returned", false);

        List<LedgerEntry> ledger = app.reports().ledger(LocalDate.now(), LocalDate.now());
        assertEquals(0, ledger.get(ledger.size() - 1).runningTotalCents(),
                "sale total must equal the sum of refunds after the whole line is returned");
        assertEquals(3, app.refunds().refundableLines(sale.id()).get(0).refundedQty());
    }

    @Test
    void cannotRefundMoreThanWasSold() {
        long rug = createProduct("OVR", 100, 0, 10);
        loginCashierWithShift(0);
        Sale sale = sellCash(rug, 2, 0, 200).sale();

        loginManagerWithShift();
        long lineId = app.refunds().refundableLines(sale.id()).get(0).line().id();
        BusinessException e = assertThrows(BusinessException.class, () -> app.refunds()
                .refund(sale.id(), List.of(new RefundLineRequest(lineId, 3)), PaymentMethod.CASH, "oops", false));
        assertEquals("Only 2 left to refund for Product OVR", e.getMessage());
    }

    @Test
    void restockPutsReturnedItemsBackOnTheShelf() {
        long coffee = createProduct("RST", 500, 0, 10);
        loginCashierWithShift(0);
        Sale sale = sellCash(coffee, 2, 0, 1_000).sale();
        assertEquals(8, stockOf(coffee));

        loginManagerWithShift();
        long lineId = app.refunds().refundableLines(sale.id()).get(0).line().id();
        app.refunds().refund(sale.id(), List.of(new RefundLineRequest(lineId, 1)), PaymentMethod.CASH, "damaged", true);

        assertEquals(9, stockOf(coffee));
    }

    @Test
    void refundRequiresAReason() {
        long coffee = createProduct("RSN", 500, 0, 10);
        loginCashierWithShift(0);
        Sale sale = sellCash(coffee, 1, 0, 500).sale();

        loginManagerWithShift();
        long lineId = app.refunds().refundableLines(sale.id()).get(0).line().id();
        assertThrows(BusinessException.class, () -> app.refunds()
                .refund(sale.id(), List.of(new RefundLineRequest(lineId, 1)), PaymentMethod.CASH, "  ", false));
    }

    // ---- shifts and reporting ----

    @Test
    void closingTheShiftReportsVarianceAgainstExpectedCash() {
        long coffee = createProduct("SHF", 500, 800, 10);
        loginCashierWithShift(10_000);
        sellCash(coffee, 2, 0, 1_200); // takes 1,080 cash

        ShiftService.ShiftClose result = app.shifts().closeShift(11_000);

        assertEquals(11_080, result.expectedCashCents());
        assertEquals(-80, result.varianceCents());
    }

    @Test
    void cannotOpenTwoShiftsForOneCashier() {
        loginCashierWithShift(0);
        assertThrows(BusinessException.class, () -> app.shifts().openShift(0));
    }

    @Test
    void stockTakeAdjustsOnlyChangedProductsAndMovementsStayConsistent() {
        long mug = createProduct("MUG", 300, 0, 10);
        app.auth().login(MANAGER, MANAGER_PASSWORD);
        int adjusted = app.inventory().stockTake(Map.of(mug, 7));

        assertEquals(1, adjusted);
        assertEquals(7, stockOf(mug));
        int movementTotal = app.inventory().movementsFor(mug).stream().mapToInt(m -> m.qtyDelta()).sum();
        assertEquals(7, movementTotal, "stock on hand must equal the sum of its movements");
    }

    @Test
    void wasteNeedsAReasonAndIsStoredAsStockOut() {
        long mug = createProduct("WST", 300, 0, 10);
        app.auth().login(MANAGER, MANAGER_PASSWORD);

        assertThrows(BusinessException.class,
                () -> app.inventory().recordMovement(mug, MovementType.WASTE, 1, ""));
        app.inventory().recordMovement(mug, MovementType.WASTE, 2, "broken in transit");

        assertEquals(8, stockOf(mug));
        assertEquals(-2, app.inventory().movementsFor(mug).get(0).qtyDelta());
    }

    @Test
    void priceChangesAreRecordedInTheAuditLog() {
        long mug = createProduct("AUD", 300, 0, 10);
        app.auth().login(MANAGER, MANAGER_PASSWORD);
        app.inventory().updateProduct(mug,
                new ProductDraft("AUD", null, "Product AUD", null, null, 600, 150, 0, 2, true));

        boolean logged = app.audit().recent(50).stream()
                .anyMatch(e -> "PRICE_CHANGE".equals(e.action()) && "3.00 -> 6.00".equals(e.detail()));
        assertTrue(logged);
    }

    @Test
    void ledgerShowsSalesAndRefundsWithARunningTotal() {
        long coffee = createProduct("LED", 500, 0, 10);
        loginCashierWithShift(0);
        Sale sale = sellCash(coffee, 2, 0, 1_000).sale();

        loginManagerWithShift();
        long lineId = app.refunds().refundableLines(sale.id()).get(0).line().id();
        app.refunds().refund(sale.id(), List.of(new RefundLineRequest(lineId, 1)), PaymentMethod.CASH, "returned", false);

        List<LedgerEntry> ledger = app.reports().ledger(LocalDate.now(), LocalDate.now());
        assertEquals(2, ledger.size());
        assertEquals("SALE", ledger.get(0).type());
        assertEquals("REFUND", ledger.get(1).type());
        assertEquals(500, ledger.get(1).runningTotalCents());
    }

    @Test
    void inactiveProductsCannotBeSold() {
        long coffee = createProduct("OFF", 500, 0, 10);
        app.auth().login(MANAGER, MANAGER_PASSWORD);
        app.inventory().updateProduct(coffee,
                new ProductDraft("OFF", null, "Product OFF", null, null, 500, 250, 0, 2, false));
        loginCashierWithShift(0);

        BusinessException e = assertThrows(BusinessException.class, () -> sellCash(coffee, 1, 0, 500));
        assertEquals("Product OFF is not for sale", e.getMessage());
        Product product = app.inventory().findProduct(coffee).orElseThrow();
        assertFalse(product.active());
    }
}
