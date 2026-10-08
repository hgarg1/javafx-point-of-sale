package com.example.app.service;

import com.example.app.db.Database;
import com.example.app.domain.Role;
import com.example.app.domain.Tender;
import com.example.app.domain.TenderCodes;
import com.example.app.domain.TenderKind;
import com.example.app.error.BusinessException;
import com.example.app.repository.ShiftRepository;
import com.example.app.repository.TenderRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The payment types the till offers. Anyone can read them, because the payment dialog is built from them; only an
 * administrator changes them. Cash is never turned off, and a payment type that has been used is turned off rather than
 * deleted, so every old payment and refund still names a tender that exists.
 */
public final class TenderService {

    static final int MAX_NAME = 40;

    private final Database db;
    private final TenderRepository tenders;
    private final ShiftRepository shifts;
    private final AuditService audit;
    private final Session session;

    TenderService(Database db, TenderRepository tenders, ShiftRepository shifts, AuditService audit, Session session) {
        this.db = db;
        this.tenders = tenders;
        this.shifts = shifts;
        this.audit = audit;
        this.session = session;
    }

    /** Every payment type in the order the till shows them, turned-off ones included. */
    public List<Tender> list() {
        session.requireUser();
        return tenders.findAll();
    }

    /** The payment types a sale can use right now, in the order the till shows them. */
    public List<Tender> offered() {
        session.requireUser();
        List<Tender> offered = new ArrayList<>();
        for (Tender tender : tenders.findAll()) {
            if (tender.enabled()) {
                offered.add(tender);
            }
        }
        return offered;
    }

    /** The tender a new payment names. Refuses one that is unknown or turned off. */
    Tender requireOffered(String code) {
        Tender tender = requireKnown(code);
        if (!tender.enabled()) {
            throw new BusinessException(tender.name() + " is turned off at this till");
        }
        return tender;
    }

    /** The tender a payment or refund names, whether or not it is turned on, so old sales stay refundable. */
    Tender requireKnown(String code) {
        return tenders.find(code).orElseThrow(() -> new BusinessException("That payment type no longer exists"));
    }

    /** Adds a payment type. Its code is generated; its kind decides how it behaves and whether it counts in the drawer. */
    public Tender create(String name, TenderKind kind) {
        session.requireAtLeast(Role.ADMIN);
        if (kind == null) {
            throw new BusinessException("Choose what kind of payment this is");
        }
        String clean = cleanName(name, null);
        return db.inTransaction(c -> {
            String code = nextCode();
            Tender tender = new Tender(code, clean, kind, true, tenders.maxSortOrder() + 1,
                    kind == TenderKind.CASH, kind.needsReference());
            tenders.insert(tender);
            audit.record("TENDER_CREATE", "tender", code, clean + " (" + kind.label() + ")");
            return tender;
        });
    }

    public void rename(String code, String name) {
        session.requireAtLeast(Role.ADMIN);
        Tender tender = requireKnown(code);
        String clean = cleanName(name, code);
        if (clean.equals(tender.name())) {
            return;
        }
        db.runInTransaction(() -> {
            tenders.updateName(code, clean);
            audit.record("TENDER_RENAME", "tender", code, tender.name() + " -> " + clean);
        });
    }

    /** Turns a payment type on or off. Cash cannot be turned off, so every till can always take cash. */
    public void setEnabled(String code, boolean enabled) {
        session.requireAtLeast(Role.ADMIN);
        Tender tender = requireKnown(code);
        if (tender.enabled() == enabled) {
            return;
        }
        if (!enabled && TenderCodes.CASH.equals(code)) {
            throw new BusinessException("Cash cannot be turned off, because every till takes cash");
        }
        db.runInTransaction(() -> {
            tenders.updateEnabled(code, enabled);
            audit.record(enabled ? "TENDER_ON" : "TENDER_OFF", "tender", code, tender.name());
        });
    }

    /**
     * Whether a payment type's cash counts in the drawer. Cash always does. Other types can change it only while no shift
     * is open, because it changes the cash the drawer is expected to hold.
     */
    public void setCountsInDrawer(String code, boolean countsInDrawer) {
        session.requireAtLeast(Role.ADMIN);
        Tender tender = requireKnown(code);
        if (tender.countsInDrawer() == countsInDrawer) {
            return;
        }
        if (tender.isCash()) {
            throw new BusinessException("Cash always counts in the drawer");
        }
        db.runInTransaction(() -> {
            if (shifts.findOpen().isPresent()) {
                throw new BusinessException("Close the open shift before changing what counts in the drawer, "
                        + "because it changes the cash the drawer should hold");
            }
            tenders.updateCountsInDrawer(code, countsInDrawer);
            audit.record("TENDER_DRAWER", "tender", code, tender.name() + (countsInDrawer ? " counts" : " does not count")
                    + " in the drawer");
        });
    }

    public void moveUp(String code) {
        move(code, -1);
    }

    public void moveDown(String code) {
        move(code, 1);
    }

    /** Moves a payment type one place in the order the till shows them. Moving the first up, or the last down, does nothing. */
    private void move(String code, int direction) {
        session.requireAtLeast(Role.ADMIN);
        Tender tender = requireKnown(code);
        List<Tender> ordered = new ArrayList<>(tenders.findAll());
        int index = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).code().equals(code)) {
                index = i;
                break;
            }
        }
        int other = index + direction;
        if (index < 0 || other < 0 || other >= ordered.size()) {
            return;
        }
        Collections.swap(ordered, index, other);
        db.runInTransaction(() -> {
            for (int i = 0; i < ordered.size(); i++) {
                Tender listed = ordered.get(i);
                if (listed.sortOrder() != i + 1) {
                    tenders.updateSortOrder(listed.code(), i + 1);
                }
            }
            audit.record("TENDER_ORDER", "tender", code, tender.name() + " moved " + (direction < 0 ? "up" : "down"));
        });
    }

    /**
     * Deletes a payment type that has never been used. Built-in types, and any type with a payment or refund against it,
     * can only be turned off.
     */
    public void delete(String code) {
        session.requireAtLeast(Role.ADMIN);
        Tender tender = requireKnown(code);
        if (tender.isBuiltIn()) {
            throw new BusinessException(tender.name() + " is a built-in payment type and cannot be deleted"
                    + (TenderCodes.CASH.equals(code) ? "" : ". Turn it off instead."));
        }
        if (tenders.usageCount(code) > 0) {
            throw new BusinessException(tender.name() + " has been used on payments or refunds, so it cannot be deleted. "
                    + "Turn it off instead.");
        }
        db.runInTransaction(() -> {
            tenders.delete(code);
            audit.record("TENDER_DELETE", "tender", code, tender.name());
        });
    }

    /** A name is 1 to 40 characters once trimmed, and no other payment type may already use it, ignoring case. */
    private String cleanName(String name, String exceptCode) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) {
            throw new BusinessException("Give the payment type a name");
        }
        if (clean.length() > MAX_NAME) {
            throw new BusinessException("The name must be " + MAX_NAME + " characters or fewer");
        }
        if (tenders.nameTaken(clean, exceptCode)) {
            throw new BusinessException("There is already a payment type called '" + clean + "'");
        }
        return clean;
    }

    /** A code for a new payment type, not used by any other. Stored on payments, so it never changes. */
    private String nextCode() {
        int number = (int) tenders.count() + 1;
        String code;
        do {
            code = String.format("TND%04d", number++);
        } while (tenders.find(code).isPresent());
        return code;
    }
}
