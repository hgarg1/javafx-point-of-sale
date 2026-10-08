package com.example.app.service;

import com.example.app.domain.Tender;
import com.example.app.domain.TenderCodes;
import com.example.app.domain.TenderKind;
import com.example.app.error.BusinessException;
import com.example.app.service.CheckoutService.PaymentRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How tender covers a total, before anything is charged. Cash is allocated last, so it only covers what the others leave. */
class PaymentAllocationTest {

    private static final Tender CASH_TENDER = new Tender(TenderCodes.CASH, "Cash", TenderKind.CASH, true, 1, true, false);
    private static final Tender CARD_TENDER = new Tender(TenderCodes.CARD, "Card", TenderKind.CARD, true, 2, false, true);

    @Test
    void cashCoversUpToTheTotalAndTheExcessIsChange() {
        // 5.00 handed over for a 3.00 sale: cash covers 3.00, and the 2.00 excess is change.
        assertEquals(List.of(300L), allocate(List.of(cash(500)), 300));
    }

    @Test
    void aCardIsChargedBeforeCashWhateverTheListOrder() {
        // Cash is listed first, but the card takes 9.00 of the 10.00 total and cash covers the last 1.00.
        assertEquals(List.of(100L, 900L), allocate(List.of(cash(400), card(900)), 1_000));
    }

    @Test
    void aCardOfTenAndCashOfFiveCoverFifteenWhicheverIsListedFirst() {
        assertEquals(List.of(1_000L, 500L), allocate(List.of(card(1_000), cash(500)), 1_500));
        assertEquals(List.of(500L, 1_000L), allocate(List.of(cash(500), card(1_000)), 1_500));
    }

    @Test
    void cashIsNotChargedWhenTheCardCoversTheTotal() {
        assertEquals(List.of(0L, 300L), allocate(List.of(cash(500), card(300)), 300));
    }

    @Test
    void aCardAfterTheTotalIsCoveredIsNotCharged() {
        assertEquals(List.of(500L, 0L), allocate(List.of(card(500), card(300)), 500));
    }

    @Test
    void cardsAreChargedInOrderAndCashTakesWhatIsLeft() {
        assertEquals(List.of(300L, 400L, 300L), allocate(List.of(card(300), card(400), cash(500)), 1_000));
    }

    @Test
    void aShortPaymentIsRefused() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> allocate(List.of(cash(400)), 600));
        assertTrue(e.getMessage().startsWith("Payment is short by"), e.getMessage());

        // The card covers 3.00 and cash 4.00 of a 10.00 total: the rest is still owed.
        BusinessException withCard = assertThrows(BusinessException.class,
                () -> allocate(List.of(cash(400), card(300)), 1_000));
        assertTrue(withCard.getMessage().startsWith("Payment is short by"), withCard.getMessage());
    }

    @Test
    void aSaleNeedsTenderUnlessItTotalsNothing() {
        PaymentCoordinator.validate(List.of(), 0);

        BusinessException none = assertThrows(BusinessException.class,
                () -> PaymentCoordinator.validate(List.of(), 600));
        assertEquals("Add a payment first", none.getMessage());

        BusinessException extra = assertThrows(BusinessException.class,
                () -> PaymentCoordinator.validate(List.of(cash(100)), 0));
        assertTrue(extra.getMessage().startsWith("Nothing to pay"), extra.getMessage());
    }

    @Test
    void aTenderMustBeMoreThanNothing() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> PaymentCoordinator.validate(List.of(cash(0)), 600));
        assertEquals("Payment amounts must be greater than zero", e.getMessage());
    }

    /** Allocates the way checkout does, with each request resolved to the tender type it names. */
    private static List<Long> allocate(List<PaymentRequest> requests, long totalCents) {
        List<Tender> resolved = requests.stream()
                .map(request -> TenderCodes.CASH.equals(request.tender()) ? CASH_TENDER : CARD_TENDER)
                .toList();
        return PaymentCoordinator.allocate(resolved, requests, totalCents);
    }

    private static PaymentRequest cash(long tenderedCents) {
        return new PaymentRequest(TenderCodes.CASH, tenderedCents, null);
    }

    private static PaymentRequest card(long amountCents) {
        return new PaymentRequest(TenderCodes.CARD, amountCents, "4111111111111111");
    }
}
