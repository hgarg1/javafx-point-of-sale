package com.example.app.service;

import com.example.app.db.Database;
import com.example.app.domain.Limits;
import com.example.app.domain.MovementType;
import com.example.app.domain.Money;
import com.example.app.domain.RefundKind;
import com.example.app.domain.Role;
import com.example.app.domain.Sale;
import com.example.app.domain.SaleLine;
import com.example.app.domain.Shift;
import com.example.app.domain.Tender;
import com.example.app.domain.TenderKind;
import com.example.app.domain.TenderTotal;
import com.example.app.domain.User;
import com.example.app.error.ApprovalRequiredException;
import com.example.app.error.BusinessException;
import com.example.app.payment.PaymentProvider;
import com.example.app.repository.RefundRepository;
import com.example.app.repository.SaleRepository;
import com.example.app.repository.ShiftRepository;
import com.example.app.repository.UserRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Refunds are partial or full per sale line and are recorded as new rows; the original sale is never edited.
 *
 * <p>Any logged-in user can process a refund, but a manager must approve it. A manager processing it approves it
 * themselves. The refund is recorded on the open shift, so it comes out of the same drawer it was paid into.
 * Each tender can only be refunded up to what it paid. A tender that has since been turned off can still be refunded,
 * because the money on an old sale is still owed.
 */
public final class RefundService {

    public record RefundLineRequest(long saleLineId, int qty) {
    }

    /** A sale line along with how much of it has already been refunded. */
    public record RefundableLine(SaleLine line, int refundedQty) {

        public int remainingQty() {
            return line.qty() - refundedQty;
        }
    }

    private final Database db;
    private final SaleRepository sales;
    private final RefundRepository refunds;
    private final ShiftRepository shifts;
    private final UserRepository users;
    private final StockLedger ledger;
    private final Map<TenderKind, PaymentProvider> providers;
    private final AuditService audit;
    private final Session session;
    private final StoredValueService storedValue;
    private final TenderService tenders;

    RefundService(Database db, SaleRepository sales, RefundRepository refunds, ShiftRepository shifts,
                  UserRepository users, StockLedger ledger, Map<TenderKind, PaymentProvider> providers,
                  AuditService audit, Session session, StoredValueService storedValue, TenderService tenders) {
        this.storedValue = storedValue;
        this.db = db;
        this.sales = sales;
        this.refunds = refunds;
        this.shifts = shifts;
        this.users = users;
        this.ledger = ledger;
        this.providers = providers;
        this.audit = audit;
        this.session = session;
        this.tenders = tenders;
    }

    public Optional<Sale> findSaleByReceipt(String receiptNo) {
        session.requireUser();
        return sales.findByReceiptNo(receiptNo == null ? "" : receiptNo.trim().toUpperCase(Locale.ROOT));
    }

    public List<RefundableLine> refundableLines(long saleId) {
        session.requireUser();
        List<RefundableLine> result = new ArrayList<>();
        for (SaleLine line : sales.findLines(saleId)) {
            result.add(new RefundableLine(line, refunds.refundedSoFar(line.id()).qty()));
        }
        return result;
    }

    /**
     * What the customer can still be refunded to each tender that took money on the sale, for the refund screen. The
     * amount is what that tender paid less what was already refunded to it, and the name is the one it was paid under.
     */
    public List<TenderTotal> refundableByTender(long saleId) {
        session.requireUser();
        Map<String, Long> refunded = refunds.refundedByTenderForSale(saleId);
        List<TenderTotal> left = new ArrayList<>();
        for (TenderTotal paid : sales.appliedByTenderForSale(saleId)) {
            left.add(new TenderTotal(paid.tender(), paid.name(),
                    paid.cents() - refunded.getOrDefault(paid.tender(), 0L)));
        }
        return left;
    }

    /**
     * Refunds the requested quantities and returns the refund id.
     * The last unit of a line is refunded for exactly the remaining amount, so repeated partial refunds never
     * leave a cent unaccounted for.
     *
     * @param tenderCode the tender to pay back to; capped at what that tender paid on this sale
     * @param restock    whether returned items go back into stock
     * @param approverId a manager's id, from {@link AuthService#verifyApprover}. Needed unless the person processing
     *                   the refund is a manager; {@link ApprovalRequiredException} is thrown otherwise.
     */
    public long refund(long saleId, List<RefundLineRequest> requests, String tenderCode, String reason,
                       boolean restock, Long approverId) {
        User processor = session.requireUser();
        User approver = approverFor(processor, approverId);
        String cleanReason = reason == null ? "" : reason.trim();
        if (cleanReason.isEmpty()) {
            throw new BusinessException("A reason is required for a refund");
        }
        if (requests.isEmpty()) {
            throw new BusinessException("Select at least one item to refund");
        }
        Tender tender = tenders.requireKnown(tenderCode);

        return db.inTransaction(c -> {
            Shift shift = shifts.findOpen().orElseThrow(() -> new BusinessException("Open a shift before processing refunds"));
            Sale sale = sales.findById(saleId).orElseThrow(() -> new BusinessException("Sale not found"));

            List<PlannedLine> planned = planLines(sale, requests);
            long total = planned.stream().mapToLong(PlannedLine::amountCents).sum();
            if (total <= 0) {
                throw new BusinessException("Nothing to refund: the selected items have no value");
            }
            checkTenderCap(saleId, tender, total);

            // The provider refund happens inside the transaction: if it fails, nothing is written.
            String originalRef = sales.firstProviderRef(saleId, tender.code())
                    .orElseThrow(() -> new BusinessException("This sale has no " + tender.name() + " payment to refund"));
            String refundRef = providers.get(tender.kind()).refund(originalRef, total);

            Instant now = Instant.now();
            long refundId = refunds.insertRefund(saleId, shift.id(), processor.id(), approver.id(), tender.code(),
                    tender.name(), cleanReason, total, now, refundRef, RefundKind.REFUND);
            if (tender.kind() == TenderKind.STORED_VALUE) {
                // Money paid off a gift card or store credit goes back onto that same card.
                storedValue.credit(refundId, originalRef, total, now);
            }
            for (PlannedLine p : planned) {
                refunds.insertLine(refundId, p.line().id(), p.qty(), p.amountCents());
                if (restock) {
                    ledger.apply(p.line().productId(), MovementType.REFUND_RETURN, p.qty(),
                            "Refund on " + sale.receiptNo(), processor.id(), saleId);
                }
            }
            audit.record("REFUND", "sale", String.valueOf(saleId),
                    sale.receiptNo() + " refund " + new Money(total).format() + " to " + tender.name()
                            + (restock ? ", restocked" : "") + ", approved by " + approver.username()
                            + ": " + cleanReason);
            return refundId;
        });
    }

    /**
     * Voids a whole sale: every item still on it goes back into stock, and each tender is paid back in full. Only while
     * the sale's shift is open, and never for a sale that issued gift cards (those cards may already have been spent).
     *
     * @return the id of the first refund row written for the void
     */
    public long voidSale(long saleId, String reason, Long approverId) {
        User processor = session.requireUser();
        User approver = approverFor(processor, approverId);
        String cleanReason = reason == null ? "" : reason.trim();
        if (cleanReason.isEmpty()) {
            throw new BusinessException("A reason is required to void a sale");
        }

        return db.inTransaction(c -> {
            Shift shift = shifts.findOpen().orElseThrow(() -> new BusinessException("Open a shift before voiding a sale"));
            Sale sale = sales.findById(saleId).orElseThrow(() -> new BusinessException("Sale not found"));
            if (sale.shiftId() != shift.id()) {
                throw new BusinessException("Only a sale from the open shift can be voided. Refund the items instead.");
            }
            if (!storedValue.issuedBy(saleId).isEmpty()) {
                throw new BusinessException("A sale that issued gift cards cannot be voided. Refund the items instead.");
            }
            if (sale.totalCents() == 0) {
                throw new BusinessException("This sale took no payment, so there is nothing to void");
            }

            List<RefundLineRequest> requests = new ArrayList<>();
            for (SaleLine line : sales.findLines(saleId)) {
                int left = line.qty() - refunds.refundedSoFar(line.id()).qty();
                if (left > 0) {
                    requests.add(new RefundLineRequest(line.id(), left));
                }
            }
            if (requests.isEmpty()) {
                throw new BusinessException("Everything on this sale has already been refunded");
            }
            List<PlannedLine> planned = planLines(sale, requests);
            long total = planned.stream().mapToLong(PlannedLine::amountCents).sum();

            Instant now = Instant.now();
            Map<String, Long> refunded = refunds.refundedByTenderForSale(saleId);
            List<OwedTender> owed = new ArrayList<>();
            for (Map.Entry<String, Long> paid : paidByTender(saleId).entrySet()) {
                long left = paid.getValue() - refunded.getOrDefault(paid.getKey(), 0L);
                if (left > 0) {
                    // The tender is looked up without its enabled flag: a payment type that was turned off still gets its money back.
                    Tender tender = tenders.requireKnown(paid.getKey());
                    String originalRef = sales.firstProviderRef(saleId, tender.code())
                            .orElseThrow(() -> new BusinessException("This sale has no " + tender.name() + " payment to void"));
                    owed.add(new OwedTender(tender, left, originalRef));
                }
            }
            // Everything is checked before any money moves, so a mismatch cannot leave a card refunded.
            if (owed.isEmpty()) {
                throw new BusinessException("Nothing is left to refund on this sale");
            }
            long returning = owed.stream().mapToLong(OwedTender::amountCents).sum();
            if (returning != total) {
                throw new BusinessException("The payments and the items on this sale do not add up, so nothing was voided");
            }

            long firstRefund = 0;
            for (OwedTender tender : owed) {
                String refundRef = providers.get(tender.tender().kind()).refund(tender.originalRef(), tender.amountCents());
                long refundId = refunds.insertRefund(saleId, shift.id(), processor.id(), approver.id(),
                        tender.tender().code(), tender.tender().name(), cleanReason, tender.amountCents(), now, refundRef,
                        RefundKind.VOID);
                if (firstRefund == 0) {
                    // Lines are written once, against the first refund row, so no line is counted twice.
                    for (PlannedLine p : planned) {
                        refunds.insertLine(refundId, p.line().id(), p.qty(), p.amountCents());
                    }
                    firstRefund = refundId;
                }
                if (tender.tender().kind() == TenderKind.STORED_VALUE) {
                    storedValue.credit(refundId, tender.originalRef(), tender.amountCents(), now);
                }
            }
            for (PlannedLine p : planned) {
                ledger.apply(p.line().productId(), MovementType.REFUND_RETURN, p.qty(),
                        "Void of " + sale.receiptNo(), processor.id(), saleId);
            }
            audit.record("VOID", "sale", String.valueOf(saleId),
                    sale.receiptNo() + " voided, " + new Money(total).format() + " returned, approved by "
                            + approver.username() + ": " + cleanReason);
            return firstRefund;
        });
    }

    /** Works out the amount for each requested line, enforcing the remaining quantity on each. */
    private List<PlannedLine> planLines(Sale sale, List<RefundLineRequest> requests) {
        Map<Long, Integer> qtyByLine = new LinkedHashMap<>();
        for (RefundLineRequest request : requests) {
            Limits.checkQuantity(request.qty(), "Refund quantity");
            qtyByLine.merge(request.saleLineId(), request.qty(), Integer::sum);
        }
        List<PlannedLine> planned = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : qtyByLine.entrySet()) {
            SaleLine line = sales.findLine(entry.getKey())
                    .filter(l -> l.saleId() == sale.id())
                    .orElseThrow(() -> new BusinessException("That item is not on this receipt"));
            RefundRepository.RefundedSoFar done = refunds.refundedSoFar(line.id());
            int remainingQty = line.qty() - done.qty();
            int qty = entry.getValue();
            if (qty > remainingQty) {
                throw new BusinessException("Only " + remainingQty + " left to refund for " + line.nameSnapshot());
            }
            long remainingAmount = line.lineTotalCents() - done.amountCents();
            long amount = qty == remainingQty
                    ? remainingAmount
                    : Money.proportionOf(line.lineTotalCents(), qty, line.qty());
            planned.add(new PlannedLine(line, qty, amount));
        }
        return planned;
    }

    /** What each tender code paid on a sale, summed over the names it was paid under. */
    private Map<String, Long> paidByTender(long saleId) {
        Map<String, Long> paid = new LinkedHashMap<>();
        for (TenderTotal total : sales.appliedByTenderForSale(saleId)) {
            paid.merge(total.tender(), total.cents(), Long::sum);
        }
        return paid;
    }

    /** A refund to one tender cannot exceed what that tender paid, less what was already refunded to it. */
    private void checkTenderCap(long saleId, Tender tender, long totalCents) {
        long paid = paidByTender(saleId).getOrDefault(tender.code(), 0L);
        long alreadyRefunded = refunds.refundedByTenderForSale(saleId).getOrDefault(tender.code(), 0L);
        long available = paid - alreadyRefunded;
        if (paid == 0) {
            throw new BusinessException("This sale was not paid by " + tender.name().toLowerCase(Locale.ROOT));
        }
        if (totalCents > available) {
            throw new BusinessException("Only " + new Money(Math.max(0, available)).format() + " can be refunded to "
                    + tender.name().toLowerCase(Locale.ROOT) + " for this sale");
        }
    }

    /** The processor's own approval for managers; otherwise an active manager's id from the approval dialog. */
    private User approverFor(User processor, Long approverId) {
        if (processor.role().atLeast(Role.MANAGER)) {
            return processor;
        }
        if (approverId == null) {
            throw new ApprovalRequiredException("A manager must approve this refund");
        }
        User approver = users.findById(approverId).orElseThrow(() -> new BusinessException("Approver not found"));
        if (!approver.active() || !approver.role().atLeast(Role.MANAGER)) {
            throw new BusinessException("Approver must be an active manager");
        }
        return approver;
    }

    private record PlannedLine(SaleLine line, int qty, long amountCents) {
    }

    /** One tender still owed money on a sale, with the provider reference its original payment was made under. */
    private record OwedTender(Tender tender, long amountCents, String originalRef) {
    }
}
