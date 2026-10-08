package com.example.app.service;

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

/** Refund rules: amounts, caps per tender, approvals, restocking and the shift a refund is recorded on. */
class RefundRulesTest {

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
    void partialRefundsNeverLoseACent() {
        // 100 each, 3 for 200 after a 33.33% discount, so the line total does not divide by 3.
        long rug = product(app, "RUG", 100, 0, 10);
        cashierOpensShift(app, 0);
        Sale sale = app.checkout().checkout(
                List.of(new CartItem(rug, 3, 3_333)),
                List.of(new PaymentRequest(TenderCodes.CASH, 200, null)),
                managerId(app)).sale();
        assertEquals(200, sale.totalCents());

        app.auth().login(MANAGER, MANAGER_PASSWORD);
        long lineId = app.refunds().refundableLines(sale.id()).get(0).line().id();
        for (int i = 0; i < 3; i++) {
            app.refunds().refund(sale.id(), List.of(new RefundLineRequest(lineId, 1)),
                    TenderCodes.CASH, "returned", false, null);
        }

        List<LedgerEntry> ledger = app.reports().ledger(LocalDate.now(), LocalDate.now());
        assertEquals(0, ledger.get(ledger.size() - 1).runningTotalCents(),
                "the sale total must equal the refunds once the whole line is returned");
        assertEquals(3, app.refunds().refundableLines(sale.id()).get(0).refundedQty());
    }

    @Test
    void cannotRefundMoreThanWasSold() {
        long rug = product(app, "OVR", 100, 0, 10);
        cashierOpensShift(app, 0);
        Sale sale = sellCash(app, rug, 2, 0, 200).sale();

        app.auth().login(MANAGER, MANAGER_PASSWORD);
        long lineId = app.refunds().refundableLines(sale.id()).get(0).line().id();
        BusinessException e = assertThrows(BusinessException.class, () -> app.refunds()
                .refund(sale.id(), List.of(new RefundLineRequest(lineId, 3)), TenderCodes.CASH, "oops", false, null));
        assertEquals("Only 2 left to refund for Product OVR", e.getMessage());
    }

    @Test
    void restockPutsReturnedItemsBackOnTheShelf() {
        long coffee = product(app, "RST", 500, 0, 10);
        cashierOpensShift(app, 0);
        Sale sale = sellCash(app, coffee, 2, 0, 1_000).sale();
        assertEquals(8, stockOf(app, coffee));

        app.auth().login(MANAGER, MANAGER_PASSWORD);
        long lineId = app.refunds().refundableLines(sale.id()).get(0).line().id();
        app.refunds().refund(sale.id(), List.of(new RefundLineRequest(lineId, 1)),
                TenderCodes.CASH, "damaged", true, null);

        assertEquals(9, stockOf(app, coffee));
    }

    @Test
    void aReasonIsRequired() {
        long coffee = product(app, "RSN", 500, 0, 10);
        cashierOpensShift(app, 0);
        Sale sale = sellCash(app, coffee, 1, 0, 500).sale();

        app.auth().login(MANAGER, MANAGER_PASSWORD);
        long lineId = app.refunds().refundableLines(sale.id()).get(0).line().id();
        assertThrows(BusinessException.class, () -> app.refunds()
                .refund(sale.id(), List.of(new RefundLineRequest(lineId, 1)), TenderCodes.CASH, "  ", false, null));
    }

    @Test
    void aCashierCanProcessARefundWhenAManagerApprovesIt() {
        long coffee = product(app, "APP", 500, 0, 10);
        cashierOpensShift(app, 0);
        Sale sale = sellCash(app, coffee, 1, 0, 500).sale();
        long lineId = app.refunds().refundableLines(sale.id()).get(0).line().id();

        assertThrows(ApprovalRequiredException.class, () -> app.refunds()
                .refund(sale.id(), List.of(new RefundLineRequest(lineId, 1)), TenderCodes.CASH, "changed mind", false, null));

        long refundId = app.refunds().refund(sale.id(), List.of(new RefundLineRequest(lineId, 1)),
                TenderCodes.CASH, "changed mind", false, managerId(app));
        assertTrue(refundId > 0);

        app.auth().login(TestServices.OWNER, TestServices.OWNER_PASSWORD);
        assertTrue(app.audit().recent(10).stream()
                .anyMatch(e -> "REFUND".equals(e.action()) && e.detail().contains("approved by " + MANAGER)));
    }

    @Test
    void refundsToATenderAreCappedAtWhatThatTenderPaid() {
        long coffee = product(app, "TND", 500, 0, 10);
        cashierOpensShift(app, 0);
        Sale sale = app.checkout().checkout(
                List.of(new CartItem(coffee, 2, 0)),
                List.of(new PaymentRequest(TenderCodes.CARD, 600, "4111111111111111"),
                        new PaymentRequest(TenderCodes.CASH, 400, null)),
                null).sale();
        long lineId = app.refunds().refundableLines(sale.id()).get(0).line().id();

        app.auth().login(MANAGER, MANAGER_PASSWORD);
        // One unit is worth 500, but only 400 was paid in cash.
        BusinessException e = assertThrows(BusinessException.class, () -> app.refunds()
                .refund(sale.id(), List.of(new RefundLineRequest(lineId, 1)), TenderCodes.CASH, "returned", false, null));
        assertEquals("Only $4.00 can be refunded to cash for this sale", e.getMessage());

        app.refunds().refund(sale.id(), List.of(new RefundLineRequest(lineId, 1)),
                TenderCodes.CARD, "returned", false, null);
    }

    @Test
    void aTenderThatPaidNothingCannotBeRefunded() {
        long coffee = product(app, "CRO", 500, 0, 10);
        cashierOpensShift(app, 0);
        Sale sale = app.checkout().checkout(
                List.of(new CartItem(coffee, 1, 0)),
                List.of(new PaymentRequest(TenderCodes.CARD, 500, "4111111111111111")),
                null).sale();
        long lineId = app.refunds().refundableLines(sale.id()).get(0).line().id();

        app.auth().login(MANAGER, MANAGER_PASSWORD);
        BusinessException e = assertThrows(BusinessException.class, () -> app.refunds()
                .refund(sale.id(), List.of(new RefundLineRequest(lineId, 1)), TenderCodes.CASH, "returned", false, null));
        assertEquals("This sale was not paid by cash", e.getMessage());
    }

    @Test
    void aRefundNeedsAnOpenShiftOnTheTerminal() {
        long coffee = product(app, "NSH", 500, 0, 10);
        cashierOpensShift(app, 0);
        Sale sale = sellCash(app, coffee, 1, 0, 500).sale();
        app.shifts().closeShift(500);

        app.auth().login(MANAGER, MANAGER_PASSWORD);
        long lineId = app.refunds().refundableLines(sale.id()).get(0).line().id();
        BusinessException e = assertThrows(BusinessException.class, () -> app.refunds()
                .refund(sale.id(), List.of(new RefundLineRequest(lineId, 1)), TenderCodes.CASH, "returned", false, null));
        assertEquals("Open a shift before processing refunds", e.getMessage());
    }
}
