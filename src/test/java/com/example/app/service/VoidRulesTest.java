package com.example.app.service;

import com.example.app.domain.ProductDraft;
import com.example.app.domain.ProductKind;
import com.example.app.domain.Sale;
import com.example.app.domain.TenderCodes;
import com.example.app.error.ApprovalRequiredException;
import com.example.app.error.BusinessException;
import com.example.app.service.CheckoutService.CartItem;
import com.example.app.service.CheckoutService.PaymentRequest;
import com.example.app.service.RefundService.RefundLineRequest;
import com.example.app.service.ReportService.LedgerEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.example.app.service.TestServices.MANAGER;
import static com.example.app.service.TestServices.MANAGER_PASSWORD;
import static com.example.app.service.TestServices.cashierOpensShift;
import static com.example.app.service.TestServices.managerId;
import static com.example.app.service.TestServices.product;
import static com.example.app.service.TestServices.sellCash;
import static com.example.app.service.TestServices.stockOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Voids: a whole sale reversed while its shift is open, restocked, paid back to each tender, and approved by a manager. */
class VoidRulesTest {

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
    void aVoidRestocksTheSaleAndPaysEveryTenderBack() {
        long rug = product(app, "VD1", 500, 0, 10);
        cashierOpensShift(app, 0);
        Sale sale = app.checkout().checkout(
                List.of(new CartItem(rug, 2, 0)),
                List.of(new PaymentRequest(TenderCodes.CARD, 600, "4111111111111111"),
                        new PaymentRequest(TenderCodes.CASH, 400, null)),
                null).sale();
        assertEquals(8, stockOf(app, rug));

        long refundId = app.refunds().voidSale(sale.id(), "Customer walked out", managerId(app));

        assertTrue(refundId > 0);
        assertEquals(10, stockOf(app, rug));
        app.auth().login(MANAGER, MANAGER_PASSWORD);
        long cashLeft = TestServices.leftByTender(app, sale.id()).get(TenderCodes.CASH);
        long cardLeft = TestServices.leftByTender(app, sale.id()).get(TenderCodes.CARD);
        assertEquals(0, cashLeft);
        assertEquals(0, cardLeft);
        List<LedgerEntry> ledger = app.reports().ledger(LocalDate.now(), LocalDate.now());
        assertEquals("VOID", ledger.get(ledger.size() - 1).type());
        assertEquals(0, ledger.get(ledger.size() - 1).runningTotalCents());
    }

    @Test
    void aVoidAfterAPartialRefundOnlyPaysBackWhatIsLeft() {
        long rug = product(app, "VD2", 500, 0, 10);
        cashierOpensShift(app, 0);
        Sale sale = sellCash(app, rug, 2, 0, 1_000).sale();
        long lineId = app.refunds().refundableLines(sale.id()).get(0).line().id();

        app.auth().login(MANAGER, MANAGER_PASSWORD);
        app.refunds().refund(sale.id(), List.of(new RefundLineRequest(lineId, 1)),
                TenderCodes.CASH, "one was faulty", false, null);
        app.refunds().voidSale(sale.id(), "the rest came back too", null);

        // The faulty unit was not restocked by its refund; the void puts the remaining unit back.
        assertEquals(9, stockOf(app, rug));
        assertEquals(2, app.refunds().refundableLines(sale.id()).get(0).refundedQty());
        long cashLeft = TestServices.leftByTender(app, sale.id()).get(TenderCodes.CASH);
        assertEquals(0, cashLeft);
    }

    @Test
    void aVoidNeedsAReasonAndAManagersApproval() {
        long rug = product(app, "VD3", 500, 0, 10);
        cashierOpensShift(app, 0);
        Sale sale = sellCash(app, rug, 1, 0, 500).sale();

        BusinessException noReason = assertThrows(BusinessException.class,
                () -> app.refunds().voidSale(sale.id(), "   ", managerId(app)));
        assertEquals("A reason is required to void a sale", noReason.getMessage());
        assertThrows(ApprovalRequiredException.class, () -> app.refunds().voidSale(sale.id(), "wrong item", null));
        assertEquals(9, stockOf(app, rug), "the refused attempts did not restock anything");
    }

    @Test
    void aSaleFromAnEarlierShiftCannotBeVoided() {
        long rug = product(app, "VD4", 500, 0, 10);
        cashierOpensShift(app, 0);
        Sale sale = sellCash(app, rug, 1, 0, 500).sale();
        app.shifts().closeShift(500);
        app.shifts().openShift(0);

        BusinessException e = assertThrows(BusinessException.class,
                () -> app.refunds().voidSale(sale.id(), "too late", managerId(app)));
        assertEquals("Only a sale from the open shift can be voided. Refund the items instead.", e.getMessage());
    }

    @Test
    void aVoidNeedsAnOpenShift() {
        long rug = product(app, "VD5", 500, 0, 10);
        cashierOpensShift(app, 0);
        Sale sale = sellCash(app, rug, 1, 0, 500).sale();
        app.shifts().closeShift(500);

        BusinessException e = assertThrows(BusinessException.class,
                () -> app.refunds().voidSale(sale.id(), "too late", managerId(app)));
        assertEquals("Open a shift before voiding a sale", e.getMessage());
    }

    @Test
    void aSaleThatIssuedAGiftCardCannotBeVoided() {
        long card = giftCardProduct("GC1", 2_500);
        cashierOpensShift(app, 0);
        Sale sale = sellCash(app, card, 1, 0, 2_500).sale();

        BusinessException e = assertThrows(BusinessException.class,
                () -> app.refunds().voidSale(sale.id(), "wrong card", managerId(app)));
        assertEquals("A sale that issued gift cards cannot be voided. Refund the items instead.", e.getMessage());
    }

    @Test
    void aSaleThatTookNoPaymentCannotBeVoided() {
        long rug = product(app, "VD6", 500, 0, 10);
        cashierOpensShift(app, 0);
        Sale sale = app.checkout().checkout(List.of(new CartItem(rug, 1, 10_000)), List.of(), managerId(app)).sale();

        BusinessException e = assertThrows(BusinessException.class,
                () -> app.refunds().voidSale(sale.id(), "free item", managerId(app)));
        assertEquals("This sale took no payment, so there is nothing to void", e.getMessage());
    }

    @Test
    void aSaleCanOnlyBeVoidedOnce() {
        long rug = product(app, "VD7", 500, 0, 10);
        cashierOpensShift(app, 0);
        Sale sale = sellCash(app, rug, 1, 0, 500).sale();
        app.auth().login(MANAGER, MANAGER_PASSWORD);
        app.refunds().voidSale(sale.id(), "first attempt", null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> app.refunds().voidSale(sale.id(), "second attempt", null));
        assertEquals("Everything on this sale has already been refunded", e.getMessage());
        assertEquals(10, stockOf(app, rug), "a second void must not restock again");
    }

    @Test
    void aVoidPutsGiftCardMoneyBackOnTheCard() {
        long card = giftCardProduct("GC2", 2_000);
        long rug = product(app, "VD8", 500, 0, 10);
        cashierOpensShift(app, 0);
        String code = sellCash(app, card, 1, 0, 2_000).giftCardCodes().get(0);
        Sale sale = app.checkout().checkout(
                List.of(new CartItem(rug, 2, 0)),
                List.of(new PaymentRequest(TenderCodes.STORED_VALUE, 1_000, code)),
                null).sale();
        assertEquals(1_000, app.storedValue().balance(code));

        app.refunds().voidSale(sale.id(), "paid with the wrong card", managerId(app));

        assertEquals(2_000, app.storedValue().balance(code));
        assertEquals(10, stockOf(app, rug));
    }

    @Test
    void aVoidIsReportedApartFromRefunds() {
        long rug = product(app, "VD9", 500, 0, 10);
        cashierOpensShift(app, 0);
        Sale sale = sellCash(app, rug, 2, 0, 1_000).sale();
        long lineId = app.refunds().refundableLines(sale.id()).get(0).line().id();

        app.auth().login(MANAGER, MANAGER_PASSWORD);
        app.refunds().refund(sale.id(), List.of(new RefundLineRequest(lineId, 1)),
                TenderCodes.CASH, "one was faulty", false, null);
        app.refunds().voidSale(sale.id(), "the rest came back too", null);

        ReportService.Dashboard board = app.reports().dashboard(LocalDate.now(), LocalDate.now());
        assertEquals(500, board.refundsCents());
        assertEquals(500, board.voidsCents());
        assertEquals(0, board.netSalesCents(), "the sale was returned in full, so nothing is left as net sales");

        ShiftService.ShiftSummary shift = app.shifts().currentSummary();
        assertEquals(500, shift.refundsCents());
        assertEquals(500, shift.voidsCents());
    }

    /** A gift card product at face value. The manager creates it and stays logged in, as {@link TestServices#product} does. */
    private long giftCardProduct(String sku, long faceValueCents) {
        app.auth().login(MANAGER, MANAGER_PASSWORD);
        return app.inventory().createProduct(new ProductDraft(sku, null, "Gift card " + sku, null, null,
                faceValueCents, 0, 0, 0, true, ProductKind.GIFT_CARD), 0);
    }
}
