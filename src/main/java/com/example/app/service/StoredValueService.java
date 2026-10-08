package com.example.app.service;

import com.example.app.domain.Money;
import com.example.app.error.BusinessException;
import com.example.app.payment.StoredValueLookup;
import com.example.app.repository.StoredValueRepository;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;

/**
 * Gift cards and store credit. A card is an account with a code; its balance is the sum of its entries. Entries are
 * written only inside the sale or refund that causes them, so a failed sale leaves the balance untouched.
 */
public final class StoredValueService implements StoredValueLookup {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CODE_DIGITS = 12;

    private final StoredValueRepository ledger;
    private final Session session;

    StoredValueService(StoredValueRepository ledger, Session session) {
        this.ledger = ledger;
        this.session = session;
    }

    @Override
    public boolean exists(String code) {
        return ledger.exists(clean(code));
    }

    @Override
    public long balance(String code) {
        return ledger.balance(clean(code));
    }

    /** The balance on a gift card or store credit code, for the till's balance check. */
    public long balanceOf(String code) {
        session.requireUser();
        if (!exists(code)) {
            throw new BusinessException("No gift card or store credit has that code");
        }
        return balance(code);
    }

    /** Takes {@code amountCents} off the card, as part of the sale {@code saleId}. Refuses if the card cannot cover it. */
    void redeem(long saleId, String code, long amountCents, Instant at) {
        String card = clean(code);
        if (!exists(card)) {
            throw new BusinessException("No gift card or store credit has that code");
        }
        long balance = balance(card);
        if (balance < amountCents) {
            throw new BusinessException("Only " + new Money(balance).format() + " is left on that card");
        }
        ledger.insert(card, "REDEEM", -amountCents, saleId, null, at);
    }

    /** Puts a new card with {@code amountCents} on it, issued by the sale {@code saleId}. */
    void issue(long saleId, String code, long amountCents, Instant at) {
        ledger.insert(code, "ISSUE", amountCents, saleId, null, at);
    }

    /** Puts a refund back on the card it was paid from. */
    void credit(long refundId, String code, long amountCents, Instant at) {
        ledger.insert(clean(code), "REFUND_CREDIT", amountCents, null, refundId, at);
    }

    /** A code that no card uses yet: twelve random digits. */
    String newCode() {
        while (true) {
            StringBuilder digits = new StringBuilder(CODE_DIGITS);
            for (int i = 0; i < CODE_DIGITS; i++) {
                digits.append(RANDOM.nextInt(10));
            }
            if (!ledger.exists(digits.toString())) {
                return digits.toString();
            }
        }
    }

    List<String> issuedBy(long saleId) {
        return ledger.issuedBy(saleId);
    }

    /** Codes are typed with spaces now and then; they are stored without them. */
    static String clean(String code) {
        return code == null ? "" : code.replaceAll("\\s", "");
    }
}
