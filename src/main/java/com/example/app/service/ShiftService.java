package com.example.app.service;

import com.example.app.db.Database;
import com.example.app.domain.Limits;
import com.example.app.domain.Money;
import com.example.app.domain.RefundKind;
import com.example.app.domain.Role;
import com.example.app.domain.Shift;
import com.example.app.domain.TenderTotal;
import com.example.app.domain.User;
import com.example.app.error.AccessDeniedException;
import com.example.app.error.BusinessException;
import com.example.app.repository.RefundRepository;
import com.example.app.repository.SaleRepository;
import com.example.app.repository.ShiftRepository;
import com.example.app.repository.TenderRepository;
import com.example.app.repository.UserRepository;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One drawer per terminal: at most one shift is open at a time, whoever opened it. A hand-over is a close followed by
 * an open. Sales and refunds are recorded on the open shift.
 */
public final class ShiftService {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault());

    /** Result of closing a shift. A positive variance means the drawer had more cash than expected. */
    public record ShiftClose(long expectedCashCents, long countedCashCents, long varianceCents) {
    }

    /**
     * Figures for one shift, shown on the shift screen and used for the close-out. Each list has one entry per payment type
     * that moved money in the shift, named as it was when the money moved.
     */
    public record ShiftSummary(
            Shift shift,
            long salesCount,
            long grossSalesCents,
            long taxOnSalesCents,
            List<TenderTotal> salesByTender,
            List<TenderTotal> refundsByTender,
            List<TenderTotal> voidsByTender,
            long expectedCashCents) {

        public long refundsCents() {
            return sumOf(refundsByTender);
        }

        /** Sales reversed in full while their shift was open. Reported apart from refunds. */
        public long voidsCents() {
            return sumOf(voidsByTender);
        }

        public long netSalesCents() {
            return grossSalesCents - refundsCents() - voidsCents();
        }

        private static long sumOf(List<TenderTotal> totals) {
            return totals.stream().mapToLong(TenderTotal::cents).sum();
        }
    }

    private final Database db;
    private final ShiftRepository shifts;
    private final SaleRepository sales;
    private final RefundRepository refunds;
    private final UserRepository users;
    private final AuditService audit;
    private final Session session;
    private final TenderRepository tenders;

    ShiftService(Database db, ShiftRepository shifts, SaleRepository sales, RefundRepository refunds,
                 UserRepository users, AuditService audit, Session session, TenderRepository tenders) {
        this.tenders = tenders;
        this.db = db;
        this.shifts = shifts;
        this.sales = sales;
        this.refunds = refunds;
        this.users = users;
        this.audit = audit;
        this.session = session;
    }

    /** The shift open on this terminal, if any. */
    public Optional<Shift> currentShift() {
        session.requireUser();
        return shifts.findOpen();
    }

    public Shift openShift(long openingFloatCents) {
        User user = session.requireUser();
        Limits.checkAmount(openingFloatCents, "Opening float");
        return db.inTransaction(c -> {
            Optional<Shift> open = shifts.findOpen();
            if (open.isPresent()) {
                throw new BusinessException("Shift already open since " + WHEN.format(open.get().openedAt())
                        + " (opened by " + usernameOf(open.get().userId()) + "). Close it before opening a new one.");
            }
            long id = shifts.insert(user.id(), Instant.now(), openingFloatCents);
            audit.record("OPEN_SHIFT", "shift", String.valueOf(id),
                    "float " + new Money(openingFloatCents).format());
            return shifts.findById(id).orElseThrow();
        });
    }

    /**
     * Closes the open shift against the counted cash. Only the person who opened it, or a manager, may close it.
     */
    public ShiftClose closeShift(long countedCashCents) {
        User user = session.requireUser();
        Limits.checkAmount(countedCashCents, "Counted cash");
        return db.inTransaction(c -> {
            Shift shift = shifts.findOpen().orElseThrow(() -> new BusinessException("No shift is open"));
            if (shift.userId() != user.id() && !user.role().atLeast(Role.MANAGER)) {
                throw new AccessDeniedException("Only the person who opened this shift, or a manager, can close it");
            }
            long expected = expectedCash(shift);
            shifts.close(shift.id(), Instant.now(), countedCashCents, expected, user.id());
            long variance = countedCashCents - expected;
            audit.record("CLOSE_SHIFT", "shift", String.valueOf(shift.id()),
                    "expected " + new Money(expected).format() + ", counted " + new Money(countedCashCents).format()
                            + ", variance " + new Money(variance).format());
            return new ShiftClose(expected, countedCashCents, variance);
        });
    }

    /** Summary of the open shift. */
    public ShiftSummary currentSummary() {
        Shift shift = currentShift().orElseThrow(() -> new BusinessException("No shift is open"));
        return summaryOf(shift);
    }

    ShiftSummary summaryOf(Shift shift) {
        SaleRepository.SalesTotals totals = sales.totalsForShift(shift.id());
        return new ShiftSummary(shift, totals.count(), totals.totalCents(), totals.taxCents(),
                sales.appliedByTenderForShift(shift.id()),
                refunds.amountByTenderForShift(shift.id(), RefundKind.REFUND),
                refunds.amountByTenderForShift(shift.id(), RefundKind.VOID),
                expectedCash(shift));
    }

    /**
     * Drawer cash that should be present: float, plus the money taken on tenders that count in the drawer, minus the money
     * given back on those tenders, as refunds or voids.
     */
    long expectedCash(Shift shift) {
        Set<String> drawer = tenders.drawerCodes();
        long taken = sum(sales.appliedByTenderForShift(shift.id()), drawer);
        long given = sum(refunds.amountByTenderForShift(shift.id(), RefundKind.REFUND), drawer)
                + sum(refunds.amountByTenderForShift(shift.id(), RefundKind.VOID), drawer);
        return shift.openingFloatCents() + taken - given;
    }

    /** Adds up the amounts on the tenders whose codes are in the drawer set. */
    private static long sum(List<TenderTotal> totals, Set<String> drawer) {
        return totals.stream()
                .filter(t -> drawer.contains(t.tender()))
                .mapToLong(TenderTotal::cents)
                .sum();
    }

    private String usernameOf(long userId) {
        return users.findById(userId).map(User::username).orElse("someone");
    }
}
