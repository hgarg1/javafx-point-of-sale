package com.example.app.service;

import com.example.app.db.Database;
import com.example.app.domain.MovementType;
import com.example.app.domain.Money;
import com.example.app.domain.Payment;
import com.example.app.domain.PaymentMethod;
import com.example.app.domain.Product;
import com.example.app.domain.Role;
import com.example.app.domain.Sale;
import com.example.app.domain.SaleLine;
import com.example.app.domain.Shift;
import com.example.app.domain.User;
import com.example.app.error.ApprovalRequiredException;
import com.example.app.error.BusinessException;
import com.example.app.payment.PaymentProvider;
import com.example.app.repository.ProductRepository;
import com.example.app.repository.SaleRepository;
import com.example.app.repository.SettingsRepository;
import com.example.app.repository.ShiftRepository;
import com.example.app.repository.UserRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Completes a sale in one transaction: validates stock and discounts, works out tax and tender,
 * authorizes payments, then writes the sale, lines, payments and stock movements together.
 * If any payment is declined, nothing is written.
 */
public final class CheckoutService {

    /** One line in the cart. {@code discountBp} of 1000 means 10% off this line. */
    public record CartItem(long productId, int qty, int discountBp) {
    }

    /**
     * One tender. For CASH, {@code tenderedCents} is the cash handed over (change is returned on the excess).
     * For CARD, it is the amount to charge and {@code reference} is the card number (simulated).
     */
    public record PaymentRequest(PaymentMethod method, long tenderedCents, String reference) {
    }

    public record SaleDetails(Sale sale, List<SaleLine> lines, List<Payment> payments) {
    }

    /** Totals for a cart, before payment. Produced by the same code that completes the sale. */
    public record Quote(long subtotalCents, long discountCents, long taxCents, long totalCents) {
    }

    private record PricedLine(Product product, CartItem item, long discountCents, long taxCents, long totalCents) {
    }

    private record PricedCart(List<PricedLine> lines, Quote quote) {
    }

    private record Applied(PaymentMethod method, long appliedCents, long tenderedCents, long changeCents,
                           String providerRef) {
    }

    private final Database db;
    private final ProductRepository products;
    private final SaleRepository sales;
    private final ShiftRepository shifts;
    private final UserRepository users;
    private final SettingsRepository settings;
    private final StockLedger ledger;
    private final Map<PaymentMethod, PaymentProvider> providers;
    private final AuditService audit;
    private final Session session;

    CheckoutService(Database db, ProductRepository products, SaleRepository sales, ShiftRepository shifts,
                    UserRepository users, SettingsRepository settings, StockLedger ledger,
                    Map<PaymentMethod, PaymentProvider> providers, AuditService audit, Session session) {
        this.db = db;
        this.products = products;
        this.sales = sales;
        this.shifts = shifts;
        this.users = users;
        this.settings = settings;
        this.ledger = ledger;
        this.providers = providers;
        this.audit = audit;
        this.session = session;
    }

    /** Totals for a cart as it would be charged. Writes nothing. */
    public Quote quote(List<CartItem> cart) {
        session.requireUser();
        return priceCart(cart, loadProducts(cart, false), discountThresholdBp()).quote();
    }

    /**
     * @param approverId id of a manager who approved large discounts; null if none was given. If a discount is
     *                   above the store threshold and this is null, {@link ApprovalRequiredException} is thrown.
     */
    public SaleDetails checkout(List<CartItem> cart, List<PaymentRequest> tenders, Long approverId) {
        User cashier = session.requireUser();
        if (cart.isEmpty()) {
            throw new BusinessException("The cart is empty");
        }
        if (tenders.isEmpty()) {
            throw new BusinessException("Add a payment first");
        }

        return db.inTransaction(c -> {
            Shift shift = shifts.findOpenByUser(cashier.id())
                    .orElseThrow(() -> new BusinessException("Open a shift before selling"));

            int threshold = discountThresholdBp();
            Map<Long, Product> productsById = loadProducts(cart, true);
            PricedCart priced = priceCart(cart, productsById, threshold);

            Long discountApprovedBy = null;
            for (CartItem item : cart) {
                if (item.discountBp() > threshold) {
                    discountApprovedBy = requireApprover(approverId, threshold).id();
                    break;
                }
            }

            List<Applied> applied = authorizeTenders(tenders, priced.quote().totalCents());

            // Write the sale. Everything below shares this transaction.
            Quote quote = priced.quote();
            String receiptNo = String.format("R%06d", sales.nextReceiptSequence());
            Instant now = Instant.now();
            long saleId = sales.insertSale(receiptNo, shift.id(), cashier.id(), discountApprovedBy,
                    quote.subtotalCents(), quote.discountCents(), quote.taxCents(), quote.totalCents(), now);
            for (PricedLine line : priced.lines()) {
                sales.insertLine(saleId, line.product().id(), line.product().name(), line.product().priceCents(),
                        line.item().qty(), line.item().discountBp(), line.discountCents(), line.taxCents(),
                        line.totalCents());
                ledger.apply(line.product().id(), MovementType.SALE, -line.item().qty(), null, cashier.id(), saleId);
            }
            for (Applied payment : applied) {
                sales.insertPayment(saleId, payment.method(), payment.appliedCents(), payment.tenderedCents(),
                        payment.changeCents(), payment.providerRef());
            }
            audit.record("SALE", "sale", String.valueOf(saleId),
                    receiptNo + " total " + new Money(quote.totalCents()).format());

            return new SaleDetails(sales.findById(saleId).orElseThrow(), sales.findLines(saleId),
                    sales.findPayments(saleId));
        });
    }

    /** Loads each product once. With {@code checkStock}, also rejects inactive products and short stock. */
    private Map<Long, Product> loadProducts(List<CartItem> cart, boolean checkStock) {
        boolean allowNegativeStock = Boolean.parseBoolean(settings.find("allow_negative_stock").orElse("false"));
        Map<Long, Integer> qtyByProduct = new LinkedHashMap<>();
        for (CartItem item : cart) {
            if (item.qty() <= 0) {
                throw new BusinessException("Quantity must be at least 1");
            }
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
                if (!allowNegativeStock && product.stockQty() < entry.getValue()) {
                    throw new BusinessException("Only " + product.stockQty() + " of " + product.name() + " in stock");
                }
            }
            productsById.put(product.id(), product);
        }
        return productsById;
    }

    /** Works out line amounts. Tax is rounded per line so the receipt adds up exactly. */
    private static PricedCart priceCart(List<CartItem> cart, Map<Long, Product> productsById, int threshold) {
        List<PricedLine> lines = new ArrayList<>();
        long subtotal = 0;
        long discountTotal = 0;
        long taxTotal = 0;
        long total = 0;
        for (CartItem item : cart) {
            Product product = productsById.get(item.productId());
            long gross = Math.multiplyExact(product.priceCents(), (long) item.qty());
            long discount = Money.percentOf(gross, item.discountBp());
            long net = gross - discount;
            long tax = Money.percentOf(net, product.taxRateBp());
            long lineTotal = net + tax;
            lines.add(new PricedLine(product, item, discount, tax, lineTotal));
            subtotal += gross;
            discountTotal += discount;
            taxTotal += tax;
            total += lineTotal;
        }
        return new PricedCart(lines, new Quote(subtotal, discountTotal, taxTotal, total));
    }

    /** Authorizes tenders in the order given. Cash is never declined; cards may be. */
    private List<Applied> authorizeTenders(List<PaymentRequest> tenders, long totalCents) {
        List<Applied> applied = new ArrayList<>();
        long remaining = totalCents;
        for (PaymentRequest request : tenders) {
            if (request.tenderedCents() <= 0) {
                throw new BusinessException("Payment amounts must be greater than zero");
            }
            if (remaining == 0) {
                break;
            }
            long amount = Math.min(request.tenderedCents(), remaining);
            String providerRef = providers.get(request.method()).authorize(amount, request.reference());
            boolean cash = request.method() == PaymentMethod.CASH;
            long tendered = cash ? request.tenderedCents() : amount;
            long change = cash ? tendered - amount : 0;
            applied.add(new Applied(request.method(), amount, tendered, change, providerRef));
            remaining -= amount;
        }
        if (remaining > 0) {
            throw new BusinessException("Payment is short by " + new Money(remaining).format());
        }
        return applied;
    }

    private User requireApprover(Long approverId, int thresholdBp) {
        if (approverId == null) {
            throw new ApprovalRequiredException(
                    "Discounts above " + thresholdBp / 100 + "% need manager approval");
        }
        User approver = users.findById(approverId)
                .orElseThrow(() -> new BusinessException("Approver not found"));
        if (!approver.active() || !approver.role().atLeast(Role.MANAGER)) {
            throw new BusinessException("Approver must be an active manager");
        }
        return approver;
    }

    private int discountThresholdBp() {
        return settings.find("discount_approval_threshold_bp").map(Integer::parseInt).orElse(1000);
    }
}
