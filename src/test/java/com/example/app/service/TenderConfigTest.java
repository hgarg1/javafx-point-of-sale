package com.example.app.service;

import com.example.app.domain.Tender;
import com.example.app.domain.TenderCodes;
import com.example.app.domain.TenderKind;
import com.example.app.error.AccessDeniedException;
import com.example.app.error.BusinessException;
import com.example.app.service.CheckoutService.CartItem;
import com.example.app.service.CheckoutService.PaymentRequest;
import com.example.app.service.CheckoutService.SaleDetails;
import com.example.app.service.RefundService.RefundLineRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.example.app.service.TestServices.CASHIER;
import static com.example.app.service.TestServices.CASHIER_PASSWORD;
import static com.example.app.service.TestServices.MANAGER;
import static com.example.app.service.TestServices.MANAGER_PASSWORD;
import static com.example.app.service.TestServices.OWNER;
import static com.example.app.service.TestServices.OWNER_PASSWORD;
import static com.example.app.service.TestServices.cashierOpensShift;
import static com.example.app.service.TestServices.leftByTender;
import static com.example.app.service.TestServices.product;
import static com.example.app.service.TestServices.stockOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Payment types: who may change them, what the till offers, and what stays true for sales already made. Built-in cash is
 * the one type that can never be turned off or deleted.
 */
class TenderConfigTest {

    private static final String CARD_NUMBER = "4111111111111111";

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
    void theTillStartsWithCashCardGiftCardsAndWalletPayments() {
        app.auth().login(CASHIER, CASHIER_PASSWORD);
        List<String> expected = List.of(TenderCodes.CASH, TenderCodes.CARD, TenderCodes.STORED_VALUE, TenderCodes.EXTERNAL);
        assertEquals(expected, codes(app.tenders().list()));
        assertEquals(expected, codes(app.tenders().offered()));
    }

    @Test
    void anAdministratorAddsAPaymentTypeThatTheTillOffers() {
        app.auth().login(OWNER, OWNER_PASSWORD);
        Tender voucher = app.tenders().create("Gift voucher", TenderKind.EXTERNAL);

        assertEquals("Gift voucher", voucher.name());
        assertFalse(voucher.isBuiltIn());
        assertTrue(voucher.enabled());
        assertTrue(voucher.requiresReference(), "a voucher payment needs its reference");
        assertFalse(voucher.countsInDrawer(), "only cash counts in the drawer by default");
        assertTrue(codes(app.tenders().offered()).contains(voucher.code()));
    }

    @Test
    void onlyAnAdministratorChangesPaymentTypes() {
        app.auth().login(MANAGER, MANAGER_PASSWORD);
        assertThrows(AccessDeniedException.class, () -> app.tenders().create("Voucher", TenderKind.EXTERNAL));
        assertThrows(AccessDeniedException.class, () -> app.tenders().rename(TenderCodes.CARD, "Bank card"));
        assertThrows(AccessDeniedException.class, () -> app.tenders().setEnabled(TenderCodes.CARD, false));
        assertThrows(AccessDeniedException.class, () -> app.tenders().setCountsInDrawer(TenderCodes.CARD, true));
        assertThrows(AccessDeniedException.class, () -> app.tenders().moveUp(TenderCodes.CARD));
        assertThrows(AccessDeniedException.class, () -> app.tenders().delete(TenderCodes.STORED_VALUE));
    }

    @Test
    void cashCannotBeTurnedOffOrDeleted() {
        app.auth().login(OWNER, OWNER_PASSWORD);
        BusinessException off = assertThrows(BusinessException.class,
                () -> app.tenders().setEnabled(TenderCodes.CASH, false));
        assertEquals("Cash cannot be turned off, because every till takes cash", off.getMessage());

        BusinessException gone = assertThrows(BusinessException.class, () -> app.tenders().delete(TenderCodes.CASH));
        assertEquals("Cash is a built-in payment type and cannot be deleted", gone.getMessage());
        assertTrue(codes(app.tenders().offered()).contains(TenderCodes.CASH));
    }

    @Test
    void aBuiltInTypeCanBeTurnedOffAndBackOnButNotDeleted() {
        app.auth().login(OWNER, OWNER_PASSWORD);
        app.tenders().setEnabled(TenderCodes.CARD, false);
        assertFalse(codes(app.tenders().offered()).contains(TenderCodes.CARD));
        assertTrue(codes(app.tenders().list()).contains(TenderCodes.CARD), "a type that is off is still listed in settings");

        BusinessException e = assertThrows(BusinessException.class, () -> app.tenders().delete(TenderCodes.CARD));
        assertEquals("Card is a built-in payment type and cannot be deleted. Turn it off instead.", e.getMessage());

        app.tenders().setEnabled(TenderCodes.CARD, true);
        assertTrue(codes(app.tenders().offered()).contains(TenderCodes.CARD));
    }

    @Test
    void anAddedCashTypeCanBeTurnedOffBecauseOnlyBuiltInCashIsKept() {
        app.auth().login(OWNER, OWNER_PASSWORD);
        Tender foreign = app.tenders().create("Foreign cash", TenderKind.CASH);
        assertTrue(foreign.countsInDrawer(), "cash always counts in the drawer");

        app.tenders().setEnabled(foreign.code(), false);
        assertFalse(codes(app.tenders().offered()).contains(foreign.code()));
    }

    @Test
    void namesAreRequiredShortEnoughAndUniqueIgnoringCase() {
        app.auth().login(OWNER, OWNER_PASSWORD);
        BusinessException blank = assertThrows(BusinessException.class,
                () -> app.tenders().create("   ", TenderKind.EXTERNAL));
        assertEquals("Give the payment type a name", blank.getMessage());

        BusinessException tooLong = assertThrows(BusinessException.class,
                () -> app.tenders().create("x".repeat(TenderService.MAX_NAME + 1), TenderKind.EXTERNAL));
        assertEquals("The name must be 40 characters or fewer", tooLong.getMessage());

        BusinessException noKind = assertThrows(BusinessException.class, () -> app.tenders().create("Voucher", null));
        assertEquals("Choose what kind of payment this is", noKind.getMessage());

        BusinessException taken = assertThrows(BusinessException.class,
                () -> app.tenders().rename(TenderCodes.EXTERNAL, "card"));
        assertEquals("There is already a payment type called 'card'", taken.getMessage());

        app.tenders().rename(TenderCodes.EXTERNAL, "Wallet");
        assertEquals("Wallet", tender(TenderCodes.EXTERNAL).name());
    }

    @Test
    void renamingChangesNewReceiptsButNotTheNameAnOldSaleWasPaidUnder() {
        long tea = product(app, "CFG-REN", 1_000, 0, 10);
        cashierOpensShift(app, 0);
        SaleDetails before = sellOneWithCard(tea);

        app.auth().login(OWNER, OWNER_PASSWORD);
        app.tenders().rename(TenderCodes.CARD, "Bank card");
        app.auth().login(CASHIER, CASHIER_PASSWORD);
        SaleDetails after = sellOneWithCard(tea);

        assertEquals("Card", before.payments().get(0).tenderName());
        assertEquals("Bank card", after.payments().get(0).tenderName());
        app.auth().login(MANAGER, MANAGER_PASSWORD);
        assertEquals("Card", app.refunds().refundableByTender(before.sale().id()).get(0).name());
    }

    @Test
    void aTurnedOffTypeIsRefusedAtTheTillButOldSalesStillRefundToIt() {
        long tea = product(app, "CFG-OFF", 1_000, 0, 10);
        cashierOpensShift(app, 0);
        SaleDetails sale = sellOneWithCard(tea);

        app.auth().login(OWNER, OWNER_PASSWORD);
        app.tenders().setEnabled(TenderCodes.CARD, false);
        app.auth().login(CASHIER, CASHIER_PASSWORD);
        BusinessException refused = assertThrows(BusinessException.class, () -> sellOneWithCard(tea));
        assertEquals("Card is turned off at this till", refused.getMessage());

        app.auth().login(MANAGER, MANAGER_PASSWORD);
        long lineId = app.refunds().refundableLines(sale.sale().id()).get(0).line().id();
        app.refunds().refund(sale.sale().id(), List.of(new RefundLineRequest(lineId, 1)),
                TenderCodes.CARD, "changed mind", true, null);

        assertEquals(10, stockOf(app, tea));
        long cardLeft = leftByTender(app, sale.sale().id()).get(TenderCodes.CARD);
        assertEquals(0, cardLeft);
    }

    @Test
    void theTillRefusesATypeThatIsOffOrUnknownAndTakesNothingFromStock() {
        long tea = product(app, "CFG-TIL", 1_000, 0, 10);
        app.auth().login(OWNER, OWNER_PASSWORD);
        app.tenders().setEnabled(TenderCodes.CARD, false);
        cashierOpensShift(app, 0);

        BusinessException off = assertThrows(BusinessException.class, () -> sellOneWithCard(tea));
        assertEquals("Card is turned off at this till", off.getMessage());

        BusinessException unknown = assertThrows(BusinessException.class, () -> app.checkout().checkout(
                List.of(new CartItem(tea, 1, 0)),
                List.of(new PaymentRequest("TND9999", 1_000, CARD_NUMBER)),
                null));
        assertEquals("That payment type no longer exists", unknown.getMessage());
        assertEquals(10, stockOf(app, tea));
    }

    @Test
    void theOrderOfTheTillMovesOneStepAtATime() {
        app.auth().login(OWNER, OWNER_PASSWORD);
        List<String> before = codes(app.tenders().list());

        app.tenders().moveUp(before.get(0));
        assertEquals(before, codes(app.tenders().list()), "the first type cannot move up");
        app.tenders().moveDown(before.get(before.size() - 1));
        assertEquals(before, codes(app.tenders().list()), "the last type cannot move down");

        app.tenders().moveUp(before.get(1));
        List<String> swapped = List.of(before.get(1), before.get(0), before.get(2), before.get(3));
        assertEquals(swapped, codes(app.tenders().list()));
        assertEquals(swapped, codes(app.tenders().offered()));
    }

    @Test
    void anUnusedAddedTypeCanBeDeletedButAUsedOneIsOnlyTurnedOff() {
        app.auth().login(OWNER, OWNER_PASSWORD);
        Tender spare = app.tenders().create("Spare", TenderKind.EXTERNAL);
        app.tenders().delete(spare.code());
        assertFalse(codes(app.tenders().list()).contains(spare.code()));
        BusinessException gone = assertThrows(BusinessException.class, () -> app.tenders().delete(spare.code()));
        assertEquals("That payment type no longer exists", gone.getMessage());

        Tender voucher = app.tenders().create("Gift voucher", TenderKind.EXTERNAL);
        long tea = product(app, "CFG-VOU", 1_000, 0, 10);
        cashierOpensShift(app, 0);
        app.checkout().checkout(
                List.of(new CartItem(tea, 1, 0)),
                List.of(new PaymentRequest(voucher.code(), 1_000, "VCH-1")),
                null);

        app.auth().login(OWNER, OWNER_PASSWORD);
        BusinessException used = assertThrows(BusinessException.class, () -> app.tenders().delete(voucher.code()));
        assertEquals("Gift voucher has been used on payments or refunds, so it cannot be deleted. Turn it off instead.",
                used.getMessage());
        app.tenders().setEnabled(voucher.code(), false);
        assertFalse(codes(app.tenders().offered()).contains(voucher.code()));
    }

    @Test
    void aTypeThatCountsInTheDrawerChangesExpectedCashOnlyWhileNoShiftIsOpen() {
        app.auth().login(OWNER, OWNER_PASSWORD);
        Tender voucher = app.tenders().create("Gift voucher", TenderKind.EXTERNAL);
        app.tenders().setCountsInDrawer(voucher.code(), true);
        long tea = product(app, "CFG-DRW", 1_000, 0, 10);
        cashierOpensShift(app, 10_000);
        app.checkout().checkout(
                List.of(new CartItem(tea, 1, 0)),
                List.of(new PaymentRequest(voucher.code(), 1_000, "VCH-2")),
                null);

        app.auth().login(OWNER, OWNER_PASSWORD);
        BusinessException locked = assertThrows(BusinessException.class,
                () -> app.tenders().setCountsInDrawer(voucher.code(), false));
        assertEquals("Close the open shift before changing what counts in the drawer, "
                + "because it changes the cash the drawer should hold", locked.getMessage());

        app.auth().login(CASHIER, CASHIER_PASSWORD);
        assertEquals(11_000, app.shifts().closeShift(11_000).expectedCashCents());
    }

    @Test
    void cashAlwaysCountsInTheDrawer() {
        app.auth().login(OWNER, OWNER_PASSWORD);
        BusinessException e = assertThrows(BusinessException.class,
                () -> app.tenders().setCountsInDrawer(TenderCodes.CASH, false));
        assertEquals("Cash always counts in the drawer", e.getMessage());
    }

    /** One tea paid for by card, the amount the card tender is asked for in these tests. */
    private SaleDetails sellOneWithCard(long productId) {
        return app.checkout().checkout(
                List.of(new CartItem(productId, 1, 0)),
                List.of(new PaymentRequest(TenderCodes.CARD, 1_000, CARD_NUMBER)),
                null);
    }

    private Tender tender(String code) {
        return app.tenders().list().stream().filter(t -> t.code().equals(code)).findFirst().orElseThrow();
    }

    private static List<String> codes(List<Tender> tenders) {
        return tenders.stream().map(Tender::code).toList();
    }
}
