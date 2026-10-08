package com.example.app.service;

import com.example.app.db.Database;
import com.example.app.domain.Limits;
import com.example.app.domain.Product;
import com.example.app.domain.Role;
import com.example.app.domain.User;
import com.example.app.error.ApprovalRequiredException;
import com.example.app.error.BusinessException;
import com.example.app.repository.HoldRepository;
import com.example.app.repository.HoldRepository.HoldLine;
import com.example.app.repository.HoldRepository.HoldRow;
import com.example.app.repository.ProductRepository;
import com.example.app.repository.SettingsRepository;
import com.example.app.repository.UserRepository;
import com.example.app.service.CheckoutService.CartItem;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parks a cart so the cashier can serve someone else, and brings it back later. A hold reserves nothing: when it is
 * recalled, every line is priced again and checked against current stock. Recalling into a cart that already has items
 * merges the held lines into it, and whatever needs attention is listed for the cashier rather than dropped quietly.
 */
public final class HoldService {

    /**
     * A parked cart as listed on the recall screen. {@code totalCents} is priced at today's prices, so it is what
     * recalling the cart would charge; it is 0 when no line can be priced.
     */
    public record HeldCart(long id, String label, Instant createdAt, String cashier, int lineCount, long totalCents) {
    }

    /**
     * A recall. {@code items} is the whole cart after the merge, in order. {@code linesAdded} counts held lines that became
     * new cart lines, and {@code linesCombined} counts held lines added to a line already on the cart.
     */
    public record Recalled(List<CartItem> items, List<String> problems, int linesAdded, int linesCombined) {
    }

    static final int MAX_LABEL = 60;

    private final Database db;
    private final HoldRepository holds;
    private final ProductRepository products;
    private final UserRepository users;
    private final SettingsRepository settings;
    private final AuditService audit;
    private final Session session;

    HoldService(Database db, HoldRepository holds, ProductRepository products, UserRepository users,
                SettingsRepository settings, AuditService audit, Session session) {
        this.db = db;
        this.holds = holds;
        this.products = products;
        this.users = users;
        this.settings = settings;
        this.audit = audit;
        this.session = session;
    }

    /** Parks the cart. Returns the id of the held sale. */
    public long hold(List<CartItem> cart, String label) {
        User user = session.requireUser();
        if (cart.isEmpty()) {
            throw new BusinessException("The cart is empty");
        }
        if (cart.size() > CheckoutService.MAX_LINES) {
            throw new BusinessException("A sale can have at most " + CheckoutService.MAX_LINES + " lines");
        }
        for (CartItem item : cart) {
            Limits.checkQuantity(item.qty(), "Quantity");
            if (item.discountBp() < 0 || item.discountBp() > 10_000) {
                throw new BusinessException("Discount must be between 0% and 100%");
            }
        }
        String clean = label == null ? "" : label.trim();
        if (clean.length() > MAX_LABEL) {
            throw new BusinessException("The label must be " + MAX_LABEL + " characters or fewer");
        }
        return db.inTransaction(c -> {
            long id = holds.insertHold(clean.isEmpty() ? null : clean, user.id(), Instant.now());
            for (CartItem item : cart) {
                holds.insertLine(id, item.productId(), item.qty(), item.discountBp());
            }
            audit.record("HOLD", "held_cart", String.valueOf(id), cart.size() + " line(s)");
            return id;
        });
    }

    /** Parked carts, oldest first. */
    public List<HeldCart> list() {
        session.requireUser();
        List<HeldCart> result = new ArrayList<>();
        for (HoldRow row : holds.findAll()) {
            result.add(toHeldCart(row));
        }
        return result;
    }

    /**
     * Brings a held cart onto the till, merged into {@code currentCart}, which is whatever is on the cart now (empty for a
     * cart with nothing on it). Every held line goes in. A held line for a product already on the cart adds its quantity to
     * that line, and the line takes the higher of the two discounts; otherwise it becomes a line of its own. Stock is checked
     * on the merged quantities, and every problem is listed. A discount above the store's approval level needs a manager: the
     * approver already on the cart is reused, and {@link ApprovalRequiredException} is thrown when there is none. The hold is
     * removed only after every check has passed, so a refused recall leaves the held sale where it was.
     *
     * @param approverId the manager already approving this cart, or the one just given; null if there is none
     */
    public Recalled recall(long holdId, List<CartItem> currentCart, Long approverId) {
        session.requireUser();
        holds.findById(holdId).orElseThrow(() -> new BusinessException("That held sale no longer exists"));
        boolean allowNegativeStock = Boolean.parseBoolean(settings.find("allow_negative_stock").orElse("false"));
        int threshold = discountThresholdBp();

        List<CartItem> merged = new ArrayList<>(currentCart);
        List<String> problems = new ArrayList<>();
        Map<Long, Product> byId = new HashMap<>();
        int added = 0;
        int combined = 0;
        for (HoldLine line : holds.findLines(holdId)) {
            Optional<Product> found = products.findById(line.productId());
            if (found.isEmpty()) {
                problems.add("A product on this held sale no longer exists and was left out");
                continue;
            }
            Product product = found.get();
            byId.put(product.id(), product);
            if (!product.active()) {
                problems.add(product.name() + " is no longer for sale; remove it before taking payment");
            }
            int index = sameProduct(merged, product.id());
            if (index >= 0) {
                CartItem existing = merged.get(index);
                int combinedQty = existing.qty() + line.qty();
                if (combinedQty > Limits.MAX_QUANTITY) {
                    throw new BusinessException(product.name() + " would have " + combinedQty
                            + " on one line, and a line can have at most " + Limits.MAX_QUANTITY
                            + ". Change the cart first; the held sale is kept.");
                }
                // The combined line keeps the higher of the two discounts, so it may need a manager's approval below.
                int discountBp = Math.max(existing.discountBp(), line.discountBp());
                merged.set(index, new CartItem(existing.productId(), combinedQty, discountBp));
                combined++;
            } else {
                merged.add(new CartItem(product.id(), line.qty(), line.discountBp()));
                added++;
            }
        }
        if (merged.size() > CheckoutService.MAX_LINES) {
            throw new BusinessException("The sale would have " + merged.size() + " lines, and a sale can have at most "
                    + CheckoutService.MAX_LINES + ". Remove some lines first; the held sale is kept.");
        }

        User approver = null;
        if (merged.stream().anyMatch(item -> item.discountBp() > threshold)) {
            approver = discountApprover(approverId, threshold);
        }

        Map<Long, Integer> totals = new HashMap<>();
        for (CartItem item : merged) {
            totals.merge(item.productId(), item.qty(), Integer::sum);
        }
        for (Map.Entry<Long, Integer> entry : totals.entrySet()) {
            Product product = byId.containsKey(entry.getKey()) ? byId.get(entry.getKey())
                    : products.findById(entry.getKey()).orElse(null);
            if (product == null) {
                problems.add("A product on this sale no longer exists; remove it before taking payment");
                continue;
            }
            if (!product.isGiftCard() && !allowNegativeStock && product.stockQty() < entry.getValue()) {
                problems.add("Only " + product.stockQty() + " of " + product.name() + " in stock now");
            }
        }

        String approvedBy = approver == null ? "" : ", approved by " + approver.username();
        String summary = added + " added, " + combined + " combined"
                + (problems.isEmpty() ? "" : ", " + problems.size() + " problem(s)") + approvedBy;
        db.runInTransaction(() -> {
            holds.delete(holdId);
            audit.record("RECALL", "held_cart", String.valueOf(holdId), summary);
        });
        return new Recalled(merged, problems, added, combined);
    }

    /** Throws away a held sale without recalling it. */
    public void discard(long holdId) {
        session.requireUser();
        holds.findById(holdId).orElseThrow(() -> new BusinessException("That held sale no longer exists"));
        db.runInTransaction(() -> {
            holds.delete(holdId);
            audit.record("DISCARD_HOLD", "held_cart", String.valueOf(holdId), null);
        });
    }

    /**
     * The position of the cart line a held line joins: the line for the same product that already has the highest discount,
     * or -1 if the cart has none for the product. Joining that line means the combined line keeps the highest discount.
     */
    private static int sameProduct(List<CartItem> cart, long productId) {
        int found = -1;
        for (int i = 0; i < cart.size(); i++) {
            CartItem item = cart.get(i);
            if (item.productId() == productId && (found < 0 || item.discountBp() > cart.get(found).discountBp())) {
                found = i;
            }
        }
        return found;
    }

    /** The manager approving the discounts on this cart. Throws when none has been given yet, so the till asks for one. */
    private User discountApprover(Long approverId, int threshold) {
        if (approverId == null) {
            throw new ApprovalRequiredException("Discounts above " + threshold / 100 + "% need manager approval");
        }
        User approver = users.findById(approverId).orElseThrow(() -> new BusinessException("Approver not found"));
        if (!approver.active() || !approver.role().atLeast(Role.MANAGER)) {
            throw new BusinessException("Approver must be an active manager");
        }
        return approver;
    }

    private int discountThresholdBp() {
        return settings.find("discount_approval_threshold_bp").map(Integer::parseInt).orElse(1000);
    }

    private HeldCart toHeldCart(HoldRow row) {
        String cashier = users.findById(row.userId()).map(User::username).orElse("(removed user)");
        return new HeldCart(row.id(), row.label(), row.createdAt(), cashier, row.lineCount(), totalOf(row.id()));
    }

    /** What the held lines cost at today's prices. Lines whose product has gone are left out. */
    private long totalOf(long holdId) {
        List<CartItem> items = new ArrayList<>();
        Map<Long, Product> byId = new HashMap<>();
        for (HoldLine line : holds.findLines(holdId)) {
            products.findById(line.productId()).ifPresent(product -> {
                byId.put(product.id(), product);
                items.add(new CartItem(product.id(), line.qty(), line.discountBp()));
            });
        }
        if (items.isEmpty()) {
            return 0;
        }
        try {
            return Pricing.price(items, byId).quote().totalCents();
        } catch (BusinessException e) {
            // A line that cannot be priced (a discounted gift card, say) is shown as 0 rather than hiding the whole list.
            return 0;
        }
    }
}
