package com.example.app.service;

import com.example.app.db.DataAccessException;
import com.example.app.domain.ProductDraft;
import com.example.app.domain.Sale;
import com.example.app.domain.TenderCodes;
import com.example.app.error.ApprovalRequiredException;
import com.example.app.error.BusinessException;
import com.example.app.payment.PaymentDeclinedException;
import com.example.app.service.CheckoutService.CartItem;
import com.example.app.service.CheckoutService.PaymentRequest;
import com.example.app.service.CheckoutService.SaleDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static com.example.app.service.TestServices.CASHIER;
import static com.example.app.service.TestServices.CASHIER_PASSWORD;
import static com.example.app.service.TestServices.MANAGER;
import static com.example.app.service.TestServices.MANAGER_PASSWORD;
import static com.example.app.service.TestServices.cashierOpensShift;
import static com.example.app.service.TestServices.idOf;
import static com.example.app.service.TestServices.managerId;
import static com.example.app.service.TestServices.product;
import static com.example.app.service.TestServices.sellCash;
import static com.example.app.service.TestServices.stockOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pricing, tender and checkout rules: what a sale costs, what is charged, and what is left behind on failure. */
class SalesRulesTest {

    private static final String VISA_LIKE = "4111 1111 1111 1111";
    private static final String DECLINED_CARD = "4111 1111 1111 0002";

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
    void cashSaleAddsTaxReturnsChangeAndDecrementsStock() {
        long coffee = product(app, "COF", 500, 800, 10);
        cashierOpensShift(app, 10_000);

        SaleDetails details = app.checkout().checkout(
                List.of(new CartItem(coffee, 2, 0)),
                List.of(new PaymentRequest(TenderCodes.CASH, 1_200, null)),
                null);

        assertEquals(1_000, details.sale().subtotalCents());
        assertEquals(80, details.sale().taxCents());
        assertEquals(1_080, details.sale().totalCents());
        assertEquals(1_080, details.payments().get(0).appliedCents());
        assertEquals(120, details.payments().get(0).changeCents());
        assertEquals(8, stockOf(app, coffee));
    }

    @Test
    void quoteMatchesTheTotalThatIsCharged() {
        long coffee = product(app, "QUO", 333, 825, 10);
        cashierOpensShift(app, 0);
        List<CartItem> cart = List.of(new CartItem(coffee, 3, 1000));

        CheckoutService.Quote quote = app.checkout().quote(cart);
        assertEquals(1, quote.lines().size());
        assertEquals(quote.totalCents(), quote.lines().get(0).totalCents());

        SaleDetails details = app.checkout().checkout(cart,
                List.of(new PaymentRequest(TenderCodes.CASH, quote.totalCents(), null)), null);
        assertEquals(quote.totalCents(), details.sale().totalCents());
    }

    @Test
    void quotingWritesNothing() {
        long coffee = product(app, "QNW", 500, 0, 10);
        cashierOpensShift(app, 0);

        app.checkout().quote(List.of(new CartItem(coffee, 4, 0)));

        assertEquals(10, stockOf(app, coffee));
        assertEquals(0, app.shifts().currentSummary().salesCount());
    }

    @Test
    void declinedCardWritesNothingAndLeavesNoHold() {
        long coffee = product(app, "DEC", 500, 0, 10);
        cashierOpensShift(app, 0);

        assertThrows(PaymentDeclinedException.class, () -> app.checkout().checkout(
                List.of(new CartItem(coffee, 1, 0)),
                List.of(new PaymentRequest(TenderCodes.CARD, 500, DECLINED_CARD)),
                null));

        assertEquals(0, app.shifts().currentSummary().salesCount());
        assertEquals(10, stockOf(app, coffee));
        assertEquals(0, app.cardProvider().liveCharges());
    }

    @Test
    void approvedCardSaleRecordsAProviderReference() {
        long coffee = product(app, "CRD", 500, 0, 10);
        cashierOpensShift(app, 0);

        SaleDetails details = app.checkout().checkout(
                List.of(new CartItem(coffee, 1, 0)),
                List.of(new PaymentRequest(TenderCodes.CARD, 500, VISA_LIKE)),
                null);

        assertTrue(details.payments().get(0).providerRef().startsWith("SIM-"));
        assertEquals(1, app.cardProvider().liveCharges());
    }

    @Test
    void theCardNumberIsNeverStoredAnywhereInTheDatabase() {
        long coffee = product(app, "PAN", 500, 0, 10);
        cashierOpensShift(app, 0);
        app.checkout().checkout(
                List.of(new CartItem(coffee, 1, 0)),
                List.of(new PaymentRequest(TenderCodes.CARD, 500, VISA_LIKE)),
                null);

        assertEquals(0, rowsContaining("4111111111111111"));
    }

    @Test
    void aShortPaymentIsRejectedBeforeAnyCardIsCharged() {
        long coffee = product(app, "SHT", 500, 0, 10);
        cashierOpensShift(app, 0);

        BusinessException e = assertThrows(BusinessException.class, () -> app.checkout().checkout(
                List.of(new CartItem(coffee, 2, 0)),
                List.of(new PaymentRequest(TenderCodes.CARD, 400, VISA_LIKE),
                        new PaymentRequest(TenderCodes.CASH, 300, null)),
                null));

        assertEquals("Payment is short by $3.00", e.getMessage());
        assertEquals(0, app.cardProvider().liveCharges());
    }

    @Test
    void aHoldOnAnEarlierCardIsReleasedWhenALaterTenderIsDeclined() {
        long coffee = product(app, "HLD", 500, 0, 10);
        cashierOpensShift(app, 0);

        assertThrows(PaymentDeclinedException.class, () -> app.checkout().checkout(
                List.of(new CartItem(coffee, 2, 0)),
                List.of(new PaymentRequest(TenderCodes.CARD, 600, VISA_LIKE),
                        new PaymentRequest(TenderCodes.CARD, 400, DECLINED_CARD)),
                null));

        assertEquals(0, app.cardProvider().liveCharges());
        assertEquals(0, app.shifts().currentSummary().salesCount());
    }

    @Test
    void aHoldIsReleasedWhenTheSaleCannotBeWritten() {
        long coffee = product(app, "WRT", 500, 0, 10);
        cashierOpensShift(app, 0);
        // Simulate a database failure after the card has been authorized: the payments table disappears.
        app.database().execute("DROP TABLE payments");

        assertThrows(DataAccessException.class, () -> app.checkout().checkout(
                List.of(new CartItem(coffee, 1, 0)),
                List.of(new PaymentRequest(TenderCodes.CARD, 500, VISA_LIKE)),
                null));

        assertEquals(0, app.cardProvider().liveCharges());
        // The summary joins the payments table, which is gone here, so count sales directly.
        assertEquals(0, app.database().queryOne("SELECT COUNT(*) FROM sales", rs -> rs.getLong(1)).orElseThrow());
        assertEquals(10, stockOf(app, coffee));
    }

    @Test
    void insufficientStockIsRejected() {
        long coffee = product(app, "OUT", 500, 0, 10);
        cashierOpensShift(app, 0);

        BusinessException e = assertThrows(BusinessException.class, () -> sellCash(app, coffee, 11, 0, 99_999));
        assertEquals("Only 10 of Product OUT in stock", e.getMessage());
    }

    @Test
    void aLargeDiscountNeedsAManagersApproval() {
        long coffee = product(app, "DSC", 500, 0, 10);
        cashierOpensShift(app, 0);
        List<CartItem> cart = List.of(new CartItem(coffee, 1, 2_000));
        List<PaymentRequest> pay = List.of(new PaymentRequest(TenderCodes.CASH, 400, null));

        assertThrows(ApprovalRequiredException.class, () -> app.checkout().checkout(cart, pay, null));
        assertEquals(10, stockOf(app, coffee), "a checkout that needs approval must not touch stock");

        SaleDetails details = app.checkout().checkout(cart, pay, managerId(app));
        assertEquals(managerId(app), details.sale().discountApprovedBy());
        assertEquals(400, details.sale().totalCents());
    }

    @Test
    void theApproverMustBeAManager() {
        long coffee = product(app, "APR", 500, 0, 10);
        long cashierId = idOf(app, CASHIER);
        cashierOpensShift(app, 0);

        BusinessException e = assertThrows(BusinessException.class, () -> app.checkout().checkout(
                List.of(new CartItem(coffee, 1, 2_000)),
                List.of(new PaymentRequest(TenderCodes.CASH, 400, null)),
                cashierId));
        assertEquals("Approver must be an active manager", e.getMessage());
    }

    @Test
    void saleLinesKeepTheNameAndPriceFromTheDayOfSale() {
        long coffee = product(app, "SNP", 500, 0, 10);
        cashierOpensShift(app, 0);
        SaleDetails details = sellCash(app, coffee, 1, 0, 500);

        app.auth().login(MANAGER, MANAGER_PASSWORD);
        app.inventory().updateProduct(coffee, new ProductDraft("SNP", null, "Renamed SNP", null, null, 900, 250, 0, 2, true));

        var line = app.refunds().refundableLines(details.sale().id()).get(0).line();
        assertEquals(500, line.unitPriceCents());
        assertEquals("Product SNP", line.nameSnapshot());
    }

    @Test
    void aSaleWithNothingToPayNeedsNoTenderAndStillTakesStock() {
        long freeSample = product(app, "FREE", 500, 0, 10);
        cashierOpensShift(app, 0);

        SaleDetails details = app.checkout().checkout(
                List.of(new CartItem(freeSample, 1, 10_000)),
                List.of(),
                managerId(app));

        assertEquals(0, details.sale().totalCents());
        assertTrue(details.payments().isEmpty());
        assertEquals(9, stockOf(app, freeSample));
    }

    @Test
    void aZeroTotalSaleRejectsATenderRatherThanSilentlyDroppingIt() {
        long freeSample = product(app, "FRE", 500, 0, 10);
        cashierOpensShift(app, 0);

        BusinessException e = assertThrows(BusinessException.class, () -> app.checkout().checkout(
                List.of(new CartItem(freeSample, 1, 10_000)),
                List.of(new PaymentRequest(TenderCodes.CASH, 100, null)),
                managerId(app)));
        assertEquals("Nothing to pay: the total is $0.00", e.getMessage());
        assertEquals(10, stockOf(app, freeSample));
    }

    @Test
    void aRetriedCheckoutWithTheSameKeyReturnsTheSaleAlreadyMade() {
        long coffee = product(app, "IDM", 500, 0, 10);
        cashierOpensShift(app, 0);
        List<CartItem> cart = List.of(new CartItem(coffee, 2, 0));
        List<PaymentRequest> pay = List.of(new PaymentRequest(TenderCodes.CASH, 1_000, null));

        Sale first = app.checkout().checkout(cart, pay, null, "till-1-attempt-1").sale();
        Sale second = app.checkout().checkout(cart, pay, null, "till-1-attempt-1").sale();

        assertEquals(first.id(), second.id());
        assertEquals(8, stockOf(app, coffee), "a double submit must not take stock twice");
        assertEquals(1, app.shifts().currentSummary().salesCount());
    }

    @Test
    void theCashierCannotCheckOutWithoutAnOpenShift() {
        long coffee = product(app, "NOS", 500, 0, 10);
        app.auth().login(CASHIER, CASHIER_PASSWORD);

        BusinessException e = assertThrows(BusinessException.class,
                () -> sellCash(app, coffee, 1, 0, 500));
        assertEquals("Open a shift before selling", e.getMessage());
    }

    /** Counts rows in any text column of any table that contain the given text. Used to check nothing was stored. */
    private int rowsContaining(String text) {
        int found = 0;
        List<String[]> columns = app.database().query(
                "SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = 'PUBLIC' AND DATA_TYPE LIKE 'CHARACTER%'",
                this::tableAndColumn);
        for (String[] column : columns) {
            found += app.database().queryOne("SELECT COUNT(*) FROM " + column[0] + " WHERE " + column[1]
                    + " LIKE ?", rs -> rs.getInt(1), "%" + text + "%").orElse(0);
        }
        return found;
    }

    private String[] tableAndColumn(ResultSet rs) throws SQLException {
        return new String[]{rs.getString("TABLE_NAME"), rs.getString("COLUMN_NAME")};
    }
}
