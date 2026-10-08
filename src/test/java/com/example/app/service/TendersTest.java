package com.example.app.service;

import com.example.app.domain.ProductDraft;
import com.example.app.domain.ProductKind;
import com.example.app.domain.TenderCodes;
import com.example.app.error.BusinessException;
import com.example.app.payment.PaymentDeclinedException;
import com.example.app.service.CheckoutService.CartItem;
import com.example.app.service.CheckoutService.PaymentRequest;
import com.example.app.service.CheckoutService.SaleDetails;
import com.example.app.service.RefundService.RefundLineRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.example.app.service.TestServices.MANAGER;
import static com.example.app.service.TestServices.MANAGER_PASSWORD;
import static com.example.app.service.TestServices.cashierOpensShift;
import static com.example.app.service.TestServices.product;
import static com.example.app.service.TestServices.stockOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gift cards, store credit, QR/wallet payments, and what each does to stock, refunds and the drawer. */
class TendersTest {

    private AppServices app;

    @BeforeEach
    void setUp() {
        app = TestServices.fresh();
    }

    @AfterEach
    void tearDown() {
        app.close();
    }

    /** Creates a gift card product at face value as the manager. Leaves the manager logged in. */
    private long giftCardProduct(long faceValueCents) {
        app.auth().login(MANAGER, MANAGER_PASSWORD);
        return app.inventory().createProduct(new ProductDraft("GC-" + faceValueCents, null,
                "Gift card " + faceValueCents, null, null, faceValueCents, 0, 800, 0, true, ProductKind.GIFT_CARD), 0);
    }

    /** Sells one gift card of the product for cash and returns its code. */
    private String sellGiftCardForCash(long giftCardProduct, long cashCents) {
        SaleDetails sale = app.checkout().checkout(
                List.of(new CartItem(giftCardProduct, 1, 0)),
                List.of(new PaymentRequest(TenderCodes.CASH, cashCents, null)),
                null);
        return sale.giftCardCodes().get(0);
    }

    @Test
    void aGiftCardSaleIssuesOneCardPerUnitAtFaceValueWithoutTaxOrStock() {
        long giftCard = giftCardProduct(2_500);
        cashierOpensShift(app, 0);

        SaleDetails sale = app.checkout().checkout(
                List.of(new CartItem(giftCard, 2, 0)),
                List.of(new PaymentRequest(TenderCodes.CASH, 5_000, null)),
                null);

        assertEquals(5_000, sale.sale().totalCents());
        assertEquals(0, sale.sale().taxCents());
        assertEquals(2, sale.giftCardCodes().size());
        for (String code : sale.giftCardCodes()) {
            assertEquals(2_500, app.storedValue().balanceOf(code));
        }
        app.auth().login(MANAGER, MANAGER_PASSWORD);
        assertEquals(0, app.inventory().movementsFor(giftCard).size(), "gift cards are not stock");
    }

    @Test
    void aGiftCardPaysForAPurchaseAndItsBalanceGoesDown() {
        long giftCard = giftCardProduct(5_000);
        long tea = product(app, "TEA", 1_000, 0, 10);
        cashierOpensShift(app, 0);
        String code = sellGiftCardForCash(giftCard, 5_000);

        app.checkout().checkout(
                List.of(new CartItem(tea, 1, 0)),
                List.of(new PaymentRequest(TenderCodes.STORED_VALUE, 1_000, code)),
                null);

        assertEquals(4_000, app.storedValue().balanceOf(code));
        assertEquals(9, stockOf(app, tea));
    }

    @Test
    void aGiftCardWithTooLittleOnItRefusesTheSaleAndNothingIsChanged() {
        long giftCard = giftCardProduct(500);
        long tea = product(app, "TEA2", 1_000, 0, 10);
        cashierOpensShift(app, 0);
        String code = sellGiftCardForCash(giftCard, 500);

        PaymentDeclinedException e = assertThrows(PaymentDeclinedException.class, () -> app.checkout().checkout(
                List.of(new CartItem(tea, 1, 0)),
                List.of(new PaymentRequest(TenderCodes.STORED_VALUE, 1_000, code)),
                null));

        assertEquals("Only $5.00 is left on that card", e.getMessage());
        assertEquals(500, app.storedValue().balanceOf(code));
        assertEquals(10, stockOf(app, tea));
        assertEquals(1, app.shifts().currentSummary().salesCount(), "only the gift card sale was recorded");
    }

    @Test
    void anUnknownGiftCardCodeIsRefused() {
        long tea = product(app, "TEA3", 1_000, 0, 10);
        cashierOpensShift(app, 0);

        PaymentDeclinedException e = assertThrows(PaymentDeclinedException.class, () -> app.checkout().checkout(
                List.of(new CartItem(tea, 1, 0)),
                List.of(new PaymentRequest(TenderCodes.STORED_VALUE, 1_000, "000000000000")),
                null));
        assertEquals("No gift card or store credit has that code", e.getMessage());
    }

    @Test
    void aQrPaymentNeedsItsWalletReference() {
        long tea = product(app, "TEA4", 1_000, 0, 10);
        cashierOpensShift(app, 0);

        BusinessException e = assertThrows(BusinessException.class, () -> app.checkout().checkout(
                List.of(new CartItem(tea, 1, 0)),
                List.of(new PaymentRequest(TenderCodes.EXTERNAL, 1_000, "  ")),
                null));
        assertEquals("Enter the reference for QR / wallet", e.getMessage());
        assertEquals(10, stockOf(app, tea));
    }

    @Test
    void aQrPaymentIsRecordedWithItsReference() {
        long tea = product(app, "TEA5", 1_000, 0, 10);
        cashierOpensShift(app, 0);

        SaleDetails sale = app.checkout().checkout(
                List.of(new CartItem(tea, 1, 0)),
                List.of(new PaymentRequest(TenderCodes.EXTERNAL, 1_000, "WALLET-7741")),
                null);

        assertEquals("EXT:WALLET-7741", sale.payments().get(0).providerRef());
        assertEquals(9, stockOf(app, tea));
    }

    @Test
    void aRefundToAGiftCardPutsTheMoneyBackOntoThatCard() {
        long giftCard = giftCardProduct(5_000);
        long tea = product(app, "TEA6", 1_000, 0, 10);
        cashierOpensShift(app, 0);
        String code = sellGiftCardForCash(giftCard, 5_000);
        var purchase = app.checkout().checkout(
                List.of(new CartItem(tea, 1, 0)),
                List.of(new PaymentRequest(TenderCodes.STORED_VALUE, 1_000, code)),
                null).sale();
        assertEquals(4_000, app.storedValue().balanceOf(code));

        app.auth().login(MANAGER, MANAGER_PASSWORD);
        long lineId = app.refunds().refundableLines(purchase.id()).get(0).line().id();
        app.refunds().refund(purchase.id(), List.of(new RefundLineRequest(lineId, 1)),
                TenderCodes.STORED_VALUE, "changed mind", true, null);

        assertEquals(5_000, app.storedValue().balanceOf(code));
    }

    @Test
    void giftCardsCannotBeDiscounted() {
        long giftCard = giftCardProduct(2_500);
        cashierOpensShift(app, 0);

        BusinessException e = assertThrows(BusinessException.class, () -> app.checkout().checkout(
                List.of(new CartItem(giftCard, 1, 1_000)),
                List.of(new PaymentRequest(TenderCodes.CASH, 2_250, null)),
                TestServices.managerId(app)));
        assertEquals("Gift cards cannot be discounted", e.getMessage());
    }

    @Test
    void theDrawerCountsOnlyCashNotGiftCardsOrWalletPayments() {
        long giftCard = giftCardProduct(5_000);
        long tea = product(app, "TEA7", 1_000, 0, 10);
        cashierOpensShift(app, 10_000);
        String code = sellGiftCardForCash(giftCard, 5_000);
        app.checkout().checkout(
                List.of(new CartItem(tea, 1, 0)),
                List.of(new PaymentRequest(TenderCodes.CASH, 1_000, null)),
                null);
        app.checkout().checkout(
                List.of(new CartItem(tea, 1, 0)),
                List.of(new PaymentRequest(TenderCodes.STORED_VALUE, 1_000, code)),
                null);

        // Float 10,000 + gift card sold for cash 5,000 + tea paid in cash 1,000. The tea paid from the card is not cash.
        assertEquals(16_000, app.shifts().closeShift(16_000).expectedCashCents());
    }
}
