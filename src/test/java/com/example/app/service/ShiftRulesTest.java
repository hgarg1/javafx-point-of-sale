package com.example.app.service;

import com.example.app.domain.Role;
import com.example.app.domain.Sale;
import com.example.app.domain.TenderCodes;
import com.example.app.error.AccessDeniedException;
import com.example.app.error.BusinessException;
import com.example.app.service.RefundService.RefundLineRequest;
import com.example.app.service.ShiftService.ShiftClose;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.example.app.service.TestServices.MANAGER;
import static com.example.app.service.TestServices.MANAGER_PASSWORD;
import static com.example.app.service.TestServices.cashierOpensShift;
import static com.example.app.service.TestServices.managerId;
import static com.example.app.service.TestServices.product;
import static com.example.app.service.TestServices.sellCash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One drawer per terminal: what is expected in it, who may open and close it. */
class ShiftRulesTest {

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
    void closingTheShiftReportsVarianceAgainstExpectedCash() {
        long coffee = product(app, "SHF", 500, 800, 10);
        cashierOpensShift(app, 10_000);
        sellCash(app, coffee, 2, 0, 1_200); // takes 1,080 in cash

        ShiftClose result = app.shifts().closeShift(11_000);

        assertEquals(11_080, result.expectedCashCents());
        assertEquals(-80, result.varianceCents());
    }

    @Test
    void cardSalesDoNotChangeTheExpectedCash() {
        long coffee = product(app, "CRD", 500, 800, 10);
        cashierOpensShift(app, 10_000);
        app.checkout().checkout(
                java.util.List.of(new CheckoutService.CartItem(coffee, 2, 0)),
                java.util.List.of(new CheckoutService.PaymentRequest(TenderCodes.CARD, 1_080, "4111111111111111")),
                null);

        assertEquals(10_000, app.shifts().closeShift(10_000).expectedCashCents());
    }

    @Test
    void cashRefundsReduceTheExpectedCash() {
        long coffee = product(app, "RFN", 500, 800, 10);
        cashierOpensShift(app, 10_000);
        Sale sale = sellCash(app, coffee, 2, 0, 1_080).sale();

        app.auth().login(MANAGER, MANAGER_PASSWORD);
        long lineId = app.refunds().refundableLines(sale.id()).get(0).line().id();
        app.refunds().refund(sale.id(), java.util.List.of(new RefundLineRequest(lineId, 1)),
                TenderCodes.CASH, "returned", false, null);

        app.auth().login(TestServices.CASHIER, TestServices.CASHIER_PASSWORD);
        assertEquals(10_000 + 1_080 - 540, app.shifts().closeShift(10_540).expectedCashCents());
    }

    @Test
    void onlyOneShiftCanBeOpenOnTheTerminal() {
        cashierOpensShift(app, 0);

        app.auth().login(MANAGER, MANAGER_PASSWORD);
        BusinessException e = assertThrows(BusinessException.class, () -> app.shifts().openShift(0));
        assertTrue(e.getMessage().contains("already open"), e.getMessage());
        assertTrue(e.getMessage().contains(TestServices.CASHIER), e.getMessage());
    }

    @Test
    void onlyTheOpenerOrAManagerCanCloseAShift() {
        cashierOpensShift(app, 0);
        TestServices.provision(app, "lee", "lee-pass-1", Role.CASHIER);

        app.auth().login("lee", "lee-pass-1");
        assertThrows(AccessDeniedException.class, () -> app.shifts().closeShift(0));

        app.auth().login(MANAGER, MANAGER_PASSWORD);
        assertEquals(0, app.shifts().closeShift(0).countedCashCents());
    }

    @Test
    void aNewShiftCanBeOpenedOnceTheLastOneIsClosed() {
        cashierOpensShift(app, 0);
        app.shifts().closeShift(0);

        app.auth().login(MANAGER, MANAGER_PASSWORD);
        assertTrue(app.shifts().openShift(5_000).isOpen());
    }

    @Test
    void theManagerCanTakeOverAfterTheCashierCloses() {
        cashierOpensShift(app, 0);
        app.shifts().closeShift(0);
        app.auth().login(MANAGER, MANAGER_PASSWORD);
        app.shifts().openShift(0);
        assertEquals(managerId(app), app.shifts().currentShift().orElseThrow().userId());
    }
}
