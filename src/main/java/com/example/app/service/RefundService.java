package com.example.app.service;

import com.example.app.db.Database;
import com.example.app.domain.MovementType;
import com.example.app.domain.Money;
import com.example.app.domain.PaymentMethod;
import com.example.app.domain.Role;
import com.example.app.domain.Sale;
import com.example.app.domain.SaleLine;
import com.example.app.domain.Shift;
import com.example.app.domain.User;
import com.example.app.error.BusinessException;
import com.example.app.repository.RefundRepository;
import com.example.app.repository.SaleRepository;
import com.example.app.repository.ShiftRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Refunds are partial or full per sale line and are recorded as new rows; the original sale is never edited.
 * Only managers and admins can refund. The refund is recorded against the approver's open shift.
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
    private final StockLedger ledger;
    private final AuditService audit;
    private final Session session;

    RefundService(Database db, SaleRepository sales, RefundRepository refunds, ShiftRepository shifts,
                  StockLedger ledger, AuditService audit, Session session) {
        this.db = db;
        this.sales = sales;
        this.refunds = refunds;
        this.shifts = shifts;
        this.ledger = ledger;
        this.audit = audit;
        this.session = session;
    }

    public Optional<Sale> findSaleByReceipt(String receiptNo) {
        session.requireAtLeast(Role.MANAGER);
        return sales.findByReceiptNo(receiptNo == null ? "" : receiptNo.trim());
    }

    public List<RefundableLine> refundableLines(long saleId) {
        session.requireAtLeast(Role.MANAGER);
        List<RefundableLine> result = new ArrayList<>();
        for (SaleLine line : sales.findLines(saleId)) {
            result.add(new RefundableLine(line, refunds.refundedSoFar(line.id()).qty()));
        }
        return result;
    }

    /**
     * Refunds the requested quantities and returns the refund id.
     * The last unit of a line is refunded for exactly the remaining amount, so repeated partial refunds
     * never leave a cent unaccounted for.
     *
     * @param restock whether returned items go back into stock
     */
    public long refund(long saleId, List<RefundLineRequest> requests, PaymentMethod method, String reason,
                       boolean restock) {
        User manager = session.requireAtLeast(Role.MANAGER);
        String cleanReason = reason == null ? "" : reason.trim();
        if (cleanReason.isEmpty()) {
            throw new BusinessException("A reason is required for a refund");
        }
        if (requests.isEmpty()) {
            throw new BusinessException("Select at least one item to refund");
        }

        return db.inTransaction(c -> {
            Shift shift = shifts.findOpenByUser(manager.id())
                    .orElseThrow(() -> new BusinessException("Open a shift before processing refunds"));
            Sale sale = sales.findById(saleId).orElseThrow(() -> new BusinessException("Sale not found"));

            Map<Long, Integer> qtyByLine = new LinkedHashMap<>();
            for (RefundLineRequest request : requests) {
                if (request.qty() <= 0) {
                    throw new BusinessException("Refund quantity must be at least 1");
                }
                qtyByLine.merge(request.saleLineId(), request.qty(), Integer::sum);
            }

            List<PlannedLine> planned = new ArrayList<>();
            long total = 0;
            for (Map.Entry<Long, Integer> entry : qtyByLine.entrySet()) {
                SaleLine line = sales.findLine(entry.getKey())
                        .filter(l -> l.saleId() == saleId)
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
                total += amount;
            }
            if (total <= 0) {
                throw new BusinessException("Nothing to refund: the selected items have no value");
            }

            Instant now = Instant.now();
            long refundId = refunds.insertRefund(saleId, shift.id(), manager.id(), manager.id(), method,
                    cleanReason, total, now);
            for (PlannedLine p : planned) {
                refunds.insertLine(refundId, p.line().id(), p.qty(), p.amountCents());
                if (restock) {
                    ledger.apply(p.line().productId(), MovementType.REFUND_RETURN, p.qty(),
                            "Refund on " + sale.receiptNo(), manager.id(), saleId);
                }
            }
            audit.record("REFUND", "sale", String.valueOf(saleId),
                    sale.receiptNo() + " refund " + new Money(total).format() + " via " + method
                            + (restock ? ", restocked" : "") + ": " + cleanReason);
            return refundId;
        });
    }

    private record PlannedLine(SaleLine line, int qty, long amountCents) {
    }
}
