package com.example.app.service;

import com.example.app.domain.Limits;
import com.example.app.domain.Money;
import com.example.app.domain.Tender;
import com.example.app.domain.TenderKind;
import com.example.app.error.BusinessException;
import com.example.app.payment.Authorization;
import com.example.app.payment.PaymentProvider;
import com.example.app.service.CheckoutService.PaymentRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Takes the money for a sale. Tender is checked and split before anything is charged, each tender is authorized with the
 * provider for its kind, and every authorization can be released again if the sale is not written.
 */
final class PaymentCoordinator {

    private static final Logger LOG = Logger.getLogger(PaymentCoordinator.class.getName());

    /** A tender that has been authorized, with what the customer handed over. */
    record Applied(Tender tender, Authorization authorization, long tenderedCents, long changeCents) {
    }

    private final Map<TenderKind, PaymentProvider> providers;

    PaymentCoordinator(Map<TenderKind, PaymentProvider> providers) {
        this.providers = providers;
    }

    /** Rejects impossible amounts before any money moves. A zero-total sale takes no tender at all. */
    static void validate(List<PaymentRequest> tenders, long totalCents) {
        if (totalCents == 0) {
            if (!tenders.isEmpty()) {
                throw new BusinessException("Nothing to pay: the total is " + new Money(0).format());
            }
            return;
        }
        if (tenders.isEmpty()) {
            throw new BusinessException("Add a payment first");
        }
        for (PaymentRequest request : tenders) {
            if (request.tenderedCents() <= 0) {
                throw new BusinessException("Payment amounts must be greater than zero");
            }
            Limits.checkAmount(request.tenderedCents(), "Payment");
        }
    }

    /**
     * How much each tender covers, returned in the order the tenders were given. Tenders that are not cash cover what they
     * were asked for first, in order. Cash comes last and covers whatever is still owed, so cash never takes money a card or
     * voucher was meant to pay, whatever order the tenders are listed in.
     */
    static List<Long> allocate(List<Tender> resolved, List<PaymentRequest> requests, long totalCents) {
        List<Long> amounts = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            amounts.add(0L);
        }
        long remaining = totalCents;
        for (boolean cashPass : new boolean[]{false, true}) {
            for (int i = 0; i < requests.size(); i++) {
                if (resolved.get(i).isCash() != cashPass) {
                    continue;
                }
                long amount = remaining == 0 ? 0 : Math.min(requests.get(i).tenderedCents(), remaining);
                amounts.set(i, amount);
                remaining -= amount;
            }
        }
        if (remaining > 0) {
            throw new BusinessException("Payment is short by " + new Money(remaining).format());
        }
        return amounts;
    }

    /** Authorizes each tender that covers part of the total. Stops at the first decline; {@code applied} lists the rest. */
    void authorize(List<Tender> resolved, List<PaymentRequest> requests, List<Long> amounts, List<Applied> applied) {
        for (int i = 0; i < requests.size(); i++) {
            long amount = amounts.get(i);
            if (amount == 0) {
                continue;
            }
            Tender tender = resolved.get(i);
            PaymentRequest request = requests.get(i);
            Authorization authorization = providers.get(tender.kind()).authorize(amount, request.reference());
            long tendered = tender.isCash() ? request.tenderedCents() : amount;
            applied.add(new Applied(tender, authorization, tendered, tender.isCash() ? tendered - amount : 0));
        }
    }

    /** Gives back every hold taken for a sale that was not written. A failure here is logged for manual follow-up. */
    void release(List<Applied> applied) {
        for (Applied payment : applied) {
            try {
                providers.get(payment.tender().kind()).voidAuthorization(payment.authorization());
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "Could not void a " + payment.tender().name() + " authorization of "
                        + payment.authorization().amountCents() + " cents; reverse it with the provider", e);
            }
        }
    }
}
