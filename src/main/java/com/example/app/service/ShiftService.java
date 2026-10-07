package com.example.app.service;

import com.example.app.db.Database;
import com.example.app.domain.Money;
import com.example.app.domain.PaymentMethod;
import com.example.app.domain.Shift;
import com.example.app.domain.User;
import com.example.app.error.BusinessException;
import com.example.app.repository.RefundRepository;
import com.example.app.repository.SaleRepository;
import com.example.app.repository.ShiftRepository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public final class ShiftService {

    /** Result of closing a shift. A positive variance means the drawer had more cash than expected. */
    public record ShiftClose(long expectedCashCents, long countedCashCents, long varianceCents) {
    }

    /** Figures for one shift, shown on the shift screen and used for the close-out. */
    public record ShiftSummary(
            Shift shift,
            long salesCount,
            long grossSalesCents,
            long taxOnSalesCents,
            Map<PaymentMethod, Long> salesByMethod,
            Map<PaymentMethod, Long> refundsByMethod,
            long expectedCashCents) {

        public long refundsCents() {
            return refundsByMethod.values().stream().mapToLong(Long::longValue).sum();
        }

        public long netSalesCents() {
            return grossSalesCents - refundsCents();
        }
    }

    private final Database db;
    private final ShiftRepository shifts;
    private final SaleRepository sales;
    private final RefundRepository refunds;
    private final AuditService audit;
    private final Session session;

    ShiftService(Database db, ShiftRepository shifts, SaleRepository sales, RefundRepository refunds,
                 AuditService audit, Session session) {
        this.db = db;
        this.shifts = shifts;
        this.sales = sales;
        this.refunds = refunds;
        this.audit = audit;
        this.session = session;
    }

    public Optional<Shift> currentShift() {
        User user = session.requireUser();
        return shifts.findOpenByUser(user.id());
    }

    public Shift openShift(long openingFloatCents) {
        User user = session.requireUser();
        if (openingFloatCents < 0) {
            throw new BusinessException("Opening float cannot be negative");
        }
        return db.inTransaction(c -> {
            if (shifts.findOpenByUser(user.id()).isPresent()) {
                throw new BusinessException("You already have an open shift");
            }
            long id = shifts.insert(user.id(), Instant.now(), openingFloatCents);
            audit.record("OPEN_SHIFT", "shift", String.valueOf(id),
                    "float " + new Money(openingFloatCents).format());
            return shifts.findById(id).orElseThrow();
        });
    }

    /** Closes the current user's open shift against the counted cash in the drawer. */
    public ShiftClose closeShift(long countedCashCents) {
        User user = session.requireUser();
        if (countedCashCents < 0) {
            throw new BusinessException("Counted cash cannot be negative");
        }
        return db.inTransaction(c -> {
            Shift shift = shifts.findOpenByUser(user.id())
                    .orElseThrow(() -> new BusinessException("You do not have an open shift"));
            long expected = expectedCash(shift);
            shifts.close(shift.id(), Instant.now(), countedCashCents, expected);
            long variance = countedCashCents - expected;
            audit.record("CLOSE_SHIFT", "shift", String.valueOf(shift.id()),
                    "expected " + new Money(expected).format() + ", counted " + new Money(countedCashCents).format()
                            + ", variance " + new Money(variance).format());
            return new ShiftClose(expected, countedCashCents, variance);
        });
    }

    /** Summary for the current user's open shift. */
    public ShiftSummary currentSummary() {
        Shift shift = currentShift().orElseThrow(() -> new BusinessException("You do not have an open shift"));
        return summaryOf(shift);
    }

    ShiftSummary summaryOf(Shift shift) {
        SaleRepository.SalesTotals totals = sales.totalsForShift(shift.id());
        Map<PaymentMethod, Long> salesByMethod = sales.appliedByMethodForShift(shift.id());
        Map<PaymentMethod, Long> refundsByMethod = refunds.amountByMethodForShift(shift.id());
        return new ShiftSummary(shift, totals.count(), totals.totalCents(), totals.taxCents(),
                salesByMethod, refundsByMethod, expectedCash(shift));
    }

    /** Drawer cash that should be present: float, plus cash taken, minus cash given back as refunds. */
    long expectedCash(Shift shift) {
        long cashSales = sales.appliedByMethodForShift(shift.id()).getOrDefault(PaymentMethod.CASH, 0L);
        long cashRefunds = refunds.amountByMethodForShift(shift.id()).getOrDefault(PaymentMethod.CASH, 0L);
        return shift.openingFloatCents() + cashSales - cashRefunds;
    }
}
