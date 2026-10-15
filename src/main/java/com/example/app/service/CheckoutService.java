package com.example.app.service;

import com.example.app.db.Database;
import com.example.app.domain.Limits;
import com.example.app.domain.MovementType;
import com.example.app.domain.Money;
import com.example.app.domain.Payment;
import com.example.app.domain.Product;
import com.example.app.domain.Role;
import com.example.app.domain.Sale;
import com.example.app.domain.SaleLine;
import com.example.app.domain.Shift;
import com.example.app.domain.Tender;
import com.example.app.domain.TenderKind;
import com.example.app.domain.User;
import com.example.app.error.ApprovalRequiredException;
import com.example.app.error.BusinessException;
import com.example.app.payment.PaymentProvider;
import com.example.app.repository.ProductRepository;
import com.example.app.repository.SaleRepository;
import com.example.app.repository.SettingsRepository;
import com.example.app.repository.ShiftRepository;
import com.example.app.repository.UserRepository;
import com.example.app.service.Pricing.PricedCart;
import com.example.app.service.Pricing.PricedLine;
import com.example.app.service.PaymentCoordinator.Applied;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Completes a sale. The order matters:
 * <ol>
 *   <li>Validate the cart, price it, check stock and shift, and check every tender is turned on and has its reference
 *       (reads only).</li>
 *   <li>Authorize every tender that takes money with its provider. Money is held, but nothing is written yet.</li>
 *   <li>Write the sale, its lines, payments and stock movements in one database transaction.</li>
 *   <li>If step 2 or 3 fails, void every authorization that was taken, so no hold outlives a failed sale.</li>
 * </ol>
 * Pricing lives in {@link Pricing} and tender handling in {@link PaymentCoordinator}; this class sequences them and writes.
 */
public final class CheckoutService {

    /** Most lines one sale may have. A cashier keying in more than this is almost certainly making a mistake. */
    static final int MAX_LINES = 200;

    /** One line in the cart. {@code discountBp} of 1000 means 10% off this line. */
    public record CartItem(long productId, int qty, int discountBp) {
    }

    /**
     * One payment. {@code tender} is the code of the payment type. For cash, {@code tenderedCents} is the cash handed over
     * (change is returned on the excess). For any other type it is the amount to take, and {@code reference} is the card
     * number, wallet reference or gift card code, which is never stored in full.
     */
    public record PaymentRequest(String tender, long tenderedCents, String reference) {
    }

    /** A completed sale. {@code giftCardCodes} are the codes of any gift cards the sale issued, in order. */
    public record SaleDetails(Sale sale, List<SaleLine> lines, List<Payment> payments, List<String> giftCardCodes) {
    }

    /** One cart line priced from current product data. */
    public record LineQuote(long productId, String name, long unitPriceCents, int qty, int discountBp,
                            long discountCents, long taxCents, long totalCents) {
    }

    /** Totals for a cart before payment. Produced by the same code that completes the sale. */
    public record Quote(List<LineQuote> lines, long subtotalCents, long discountCents, long taxCents,
                        long totalCents) {
    }

    private final Database db;
    private final ProductRepository products;
    private final SaleRepository sales;
    private final ShiftRepository shifts;
    private final UserRepository users;
    private final SettingsRepository settings;
    private final StockLedger ledger;
    private final AuditService audit;
    private final Session session;
    private final StoredValueService storedValue;
    private final TenderService tenders;
    private final PaymentCoordinator payments;

    CheckoutService(Database db, ProductRepository products, SaleRepository sales, ShiftRepository shifts,
                    UserRepository users, SettingsRepository settings, StockLedger ledger,
                    Map<TenderKind, PaymentProvider> providers, AuditService audit, Session session,
                    StoredValueService storedValue, TenderService tenders) {
        this.db = db;
        this.products = products;
        this.sales = sales;
        this.shifts = shifts;
        this.users = users;
        this.settings = settings;
        this.ledger = ledger;
        this.audit = audit;
        this.session = session;
        this.storedValue = storedValue;
        this.tenders = tenders;
        this.payments = new PaymentCoordinator(providers);
    }

    /** Checks availability without reserving stock or changing data. Checkout repeats these checks before charging. */
    public void validateAvailability(List<CartItem> cart) {
        session.requireUser();
        if (cart.size() > MAX_LINES) {
            throw new BusinessException("A sale can have at most " + MAX_LINES + " lines");
        }
        loadProducts(cart, true);
    }

    /** Totals for a cart as it would be charged, priced from current product data. Writes nothing. */
    public Quote quote(List<CartItem> cart) {
        session.requireUser();
        if (cart.isEmpty()) {
            return new Quote(List.of(), 0, 0, 0, 0);
        }
        return Pricing.price(cart, loadProducts(cart, false)).quote();
    }

    public SaleDetails checkout(List<CartItem> cart, List<PaymentRequest> tenderRequests, Long approverId) {
        return checkout(cart, tenderRequests, approverId, null);
    }

    /** A completed sale with its lines and payments, for reprinting a receipt. */
    public SaleDetails saleDetails(long saleId) {
        session.requireUser();
        sales.findById(saleId).orElseThrow(() -> new BusinessException("Sale not found"));
        return detailsOf(saleId);
    }

    /**
     * @param approverId     a manager who approved large discounts. Required when a line discount is above the
     *                       store threshold; {@link ApprovalRequiredException} is thrown if it is missing.
     * @param idempotencyKey a key chosen for this attempt. A repeat with the same key returns the sale that was
     *                       already made, so a double-click cannot charge twice. May be null.
     */
    public SaleDetails checkout(List<CartItem> cart, List<PaymentRequest> tenderRequests, Long approverId,
                                String idempotencyKey) {
        User cashier = session.requireUser();
        if (idempotencyKey != null) {
            Optional<Sale> earlier = sales.findByIdempotencyKey(idempotencyKey);
            if (earlier.isPresent()) {
                return detailsOf(earlier.get().id());
            }
        }
        if (cart.isEmpty()) {
            throw new BusinessException("The cart is empty");
        }
        if (cart.size() > MAX_LINES) {
            throw new BusinessException("A sale can have at most " + MAX_LINES + " lines");
        }
        Shift shift = shifts.findOpen().orElseThrow(() -> new BusinessException("Open a shift before selling"));

        int threshold = discountThresholdBp();
        PricedCart priced = Pricing.price(cart, loadProducts(cart, true));
        Quote quote = priced.quote();
        List<Tender> resolved = resolveTenders(tenderRequests);
        PaymentCoordinator.validate(tenderRequests, quote.totalCents());
        List<Long> amounts = PaymentCoordinator.allocate(resolved, tenderRequests, quote.totalCents());
        Long discountApprovedBy = discountApprover(cart, approverId, threshold);

        List<Applied> applied = new ArrayList<>();
        try {
            payments.authorize(resolved, tenderRequests, amounts, applied);
            return db.inTransaction(c -> writeSale(shift, cashier, priced, applied, discountApprovedBy, idempotencyKey));
        } catch (RuntimeException e) {
            payments.release(applied);
            throw e;
        }
    }

    /**
     * Checks every payment names a tender that is turned on, and that a tender which needs a reference has one. Refusing
     * here, on the server, means no client can take money through a tender the store has switched off.
     */
    private List<Tender> resolveTenders(List<PaymentRequest> requests) {
        List<Tender> resolved = new ArrayList<>();
        for (PaymentRequest request : requests) {
            Tender tender = tenders.requireOffered(request.tender());
            if (tender.requiresReference() && (request.reference() == null || request.reference().isBlank())) {
                throw new BusinessException("Enter the reference for " + tender.name());
            }
            resolved.add(tender);
        }
        return resolved;
    }

    /** Loads each product once. With {@code checkStock}, also rejects inactive products and short stock. */
    private Map<Long, Product> loadProducts(List<CartItem> cart, boolean checkStock) {
        boolean allowNegativeStock = Boolean.parseBoolean(settings.find("allow_negative_stock").orElse("false"));
        Map<Long, Integer> qtyByProduct = new LinkedHashMap<>();
        for (CartItem item : cart) {
            Limits.checkQuantity(item.qty(), "Quantity");
            if (item.discountBp() < 0 || item.discountBp() > 10_000) {
                throw new BusinessException("Discount must be between 0% and 100%");
            }
            qtyByProduct.merge(item.productId(), item.qty(), Integer::sum);
        }
        Map<Long, Product> productsById = new LinkedHashMap<>();
        for (Map.Entry<Long, Integer> entry : qtyByProduct.entrySet()) {
            Product product = products.findById(entry.getKey())
                    .orElseThrow(() -> new BusinessException("A product in the cart no longer exists"));
            if (checkStock) {
                if (!product.active()) {
                    throw new BusinessException(product.name() + " is not for sale");
                }
                if (!allowNegativeStock && !product.isGiftCard() && product.stockQty() < entry.getValue()) {
                    throw new BusinessException("Only " + product.stockQty() + " of " + product.name() + " in stock");
                }
            }
            productsById.put(product.id(), product);
        }
        return productsById;
    }

    /** Writes the sale inside the transaction. Returns the saved sale. */
    private SaleDetails writeSale(Shift shift, User cashier, PricedCart priced, List<Applied> applied,
                                  Long discountApprovedBy, String idempotencyKey) {
        Quote quote = priced.quote();
        Instant now = Instant.now();
        String receiptNo = String.format(Locale.ROOT, "R%06d", sales.nextReceiptSequence());
        long saleId = sales.insertSale(receiptNo, shift.id(), cashier.id(), discountApprovedBy,
                quote.subtotalCents(), quote.discountCents(), quote.taxCents(), quote.totalCents(),
                now, idempotencyKey);
        for (PricedLine line : priced.lines()) {
            sales.insertLine(saleId, line.product().id(), line.product().name(), line.product().priceCents(),
                    line.item().qty(), line.item().discountBp(), line.discountCents(), line.taxCents(),
                    line.totalCents());
            if (line.product().isGiftCard()) {
                // Each gift card unit becomes a card with its face value on it. Gift cards are not stock.
                for (int unit = 0; unit < line.item().qty(); unit++) {
                    storedValue.issue(saleId, storedValue.newCode(), line.product().priceCents(), now);
                }
            } else {
                ledger.apply(line.product().id(), MovementType.SALE, -line.item().qty(), null, cashier.id(), saleId);
            }
        }
        for (Applied payment : applied) {
            if (payment.tender().kind() == TenderKind.STORED_VALUE) {
                storedValue.redeem(saleId, payment.authorization().reference(),
                        payment.authorization().amountCents(), now);
            }
            sales.insertPayment(saleId, payment.tender().code(), payment.tender().name(),
                    payment.authorization().amountCents(), payment.tenderedCents(), payment.changeCents(),
                    payment.authorization().reference());
        }
        audit.record("SALE", "sale", String.valueOf(saleId),
                receiptNo + " total " + new Money(quote.totalCents()).format());
        return detailsOf(saleId);
    }

    /**
     * The approver id if any line discount is above the threshold (the approver is checked), otherwise null.
     */
    private Long discountApprover(List<CartItem> cart, Long approverId, int threshold) {
        boolean needsApproval = cart.stream().anyMatch(item -> item.discountBp() > threshold);
        if (!needsApproval) {
            return null;
        }
        if (approverId == null) {
            throw new ApprovalRequiredException("Discounts above " + threshold / 100 + "% need manager approval");
        }
        User approver = users.findById(approverId).orElseThrow(() -> new BusinessException("Approver not found"));
        if (!approver.active() || !approver.role().atLeast(Role.MANAGER)) {
            throw new BusinessException("Approver must be an active manager");
        }
        return approver.id();
    }

    private SaleDetails detailsOf(long saleId) {
        return new SaleDetails(sales.findById(saleId).orElseThrow(), sales.findLines(saleId),
                sales.findPayments(saleId), storedValue.issuedBy(saleId));
    }

    /** Discounts above this need a manager's approval, in basis points (1000 = 10%). */
    public int discountApprovalThresholdBp() {
        session.requireUser();
        return discountThresholdBp();
    }

    private int discountThresholdBp() {
        return settings.find("discount_approval_threshold_bp").map(Integer::parseInt).orElse(1000);
    }
}
