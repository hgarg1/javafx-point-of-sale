package com.example.app.service;

import com.example.app.db.Database;
import com.example.app.domain.Limits;
import com.example.app.domain.MovementType;
import com.example.app.domain.Money;
import com.example.app.domain.Product;
import com.example.app.domain.PurchaseStatus;
import com.example.app.domain.Role;
import com.example.app.domain.Supplier;
import com.example.app.domain.User;
import com.example.app.error.BusinessException;
import com.example.app.repository.CatalogRepository;
import com.example.app.repository.ProductRepository;
import com.example.app.repository.PurchaseRepository;
import com.example.app.repository.PurchaseRepository.LineRow;
import com.example.app.repository.PurchaseRepository.OrderRow;
import com.example.app.util.CsvWriter;
import com.example.app.util.CsvWriter.Text;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Buying stock. An order goes from draft to ordered, is received in one or more deliveries, and can be cancelled only
 * while nothing has arrived. Stock changes only through {@link StockLedger}, as RECEIVE movements that point at their
 * goods receipt, and each delivery moves the average cost of the product it adds to.
 */
public final class PurchasingService {

    /** An order as listed. {@code totalCents} is the value of everything ordered on it. */
    public record OrderSummary(long id, long supplierId, String supplierName, String note, PurchaseStatus status,
                               Instant createdAt, int qtyOrdered, int qtyReceived, long totalCents) {
    }

    /** One product on an order. */
    public record OrderLine(long id, long productId, String sku, String name, int qtyOrdered, int qtyReceived,
                            long unitCostCents) {

        public int qtyRemaining() {
            return qtyOrdered - qtyReceived;
        }

        public long lineTotalCents() {
            return Math.multiplyExact((long) qtyOrdered, unitCostCents);
        }
    }

    /** An order with its lines, and how many deliveries have been booked against it. */
    public record OrderDetail(OrderSummary summary, List<OrderLine> lines, long receiptCount) {
    }

    /** One line of a delivery: how many arrived, and what they cost. */
    public record ReceiveLine(long orderLineId, int qty, long unitCostCents) {
    }

    /**
     * A product at or below its reorder level. {@code supplierId} is null when the product has no supplier, and such a
     * product cannot be put on an order until one is set.
     */
    public record Suggestion(Long supplierId, String supplierName, long productId, String sku, String name, int onHand,
                             int suggestedQty, long unitCostCents) {
    }

    static final int MAX_NOTE = 200;
    static final int MAX_INVOICE = 64;
    static final String NO_SUPPLIER = "(no supplier)";

    private final Database db;
    private final PurchaseRepository orders;
    private final ProductRepository products;
    private final CatalogRepository catalog;
    private final StockLedger ledger;
    private final AuditService audit;
    private final Session session;

    PurchasingService(Database db, PurchaseRepository orders, ProductRepository products, CatalogRepository catalog,
                      StockLedger ledger, AuditService audit, Session session) {
        this.db = db;
        this.orders = orders;
        this.products = products;
        this.catalog = catalog;
        this.ledger = ledger;
        this.audit = audit;
        this.session = session;
    }

    /** Starts a draft order with a supplier. Returns the order id. */
    public long createDraft(long supplierId, String note) {
        User manager = session.requireAtLeast(Role.MANAGER);
        String cleanNote = blankToNull(note);
        if (cleanNote != null && cleanNote.length() > MAX_NOTE) {
            throw new BusinessException("The note must be " + MAX_NOTE + " characters or fewer");
        }
        return db.inTransaction(c -> {
            Supplier supplier = catalog.findSupplier(supplierId)
                    .orElseThrow(() -> new BusinessException("Choose a supplier for this order"));
            long id = orders.insertOrder(supplierId, cleanNote, manager.id(), Instant.now());
            audit.record("PO_CREATE", "purchase_order", String.valueOf(id), supplier.name());
            return id;
        });
    }

    /** Adds a product to a draft order. Returns the id of the new line. */
    public long addLine(long orderId, long productId, int qty, long unitCostCents) {
        session.requireAtLeast(Role.MANAGER);
        if (qty <= 0) {
            throw new BusinessException("Quantity must be at least 1");
        }
        Limits.checkQuantity(qty, "Quantity");
        Limits.checkAmount(unitCostCents, "Unit cost");
        return db.inTransaction(c -> {
            requireDraft(requireOrder(orderId), "Only a draft can be changed");
            Product product = products.findById(productId)
                    .orElseThrow(() -> new BusinessException("Product no longer exists"));
            if (product.isGiftCard()) {
                throw new BusinessException("Gift cards are not stocked, so they cannot be ordered");
            }
            if (!product.active()) {
                throw new BusinessException(product.name() + " is not for sale, so it cannot be ordered");
            }
            if (orders.findLines(orderId).stream().anyMatch(l -> l.productId() == productId)) {
                throw new BusinessException(product.name() + " is already on this order. Remove that line first.");
            }
            return orders.insertLine(orderId, productId, qty, unitCostCents);
        });
    }

    /** Takes a line off a draft order. */
    public void removeLine(long lineId) {
        session.requireAtLeast(Role.MANAGER);
        db.runInTransaction(() -> {
            long orderId = orders.orderIdOfLine(lineId)
                    .orElseThrow(() -> new BusinessException("That line no longer exists"));
            requireDraft(requireOrder(orderId), "Only a draft can be changed");
            orders.deleteLine(lineId);
        });
    }

    /** Sends a draft order. It can no longer be changed, and deliveries can be booked against it. */
    public void order(long orderId) {
        session.requireAtLeast(Role.MANAGER);
        db.runInTransaction(() -> {
            OrderRow order = requireOrder(orderId);
            requireDraft(order, "Only a draft can be ordered");
            if (orders.findLines(orderId).isEmpty()) {
                throw new BusinessException("Add at least one item before ordering");
            }
            orders.markOrdered(orderId, Instant.now());
            audit.record("PO_ORDER", "purchase_order", String.valueOf(orderId), order.supplierName());
        });
    }

    /**
     * Books a delivery against an ordered purchase order. Lines can arrive in part, and the order is PARTIAL until
     * everything has come in. Every quantity is checked before any stock moves. Returns the receipt id.
     */
    public long receive(long orderId, String invoiceNo, List<ReceiveLine> lines) {
        User manager = session.requireAtLeast(Role.MANAGER);
        String invoice = blankToNull(invoiceNo);
        if (invoice != null && invoice.length() > MAX_INVOICE) {
            throw new BusinessException("The invoice number must be " + MAX_INVOICE + " characters or fewer");
        }
        List<ReceiveLine> delivered = lines.stream().filter(l -> l.qty() > 0).toList();
        if (delivered.isEmpty()) {
            throw new BusinessException("Enter a quantity for at least one item");
        }
        Set<Long> seen = new HashSet<>();
        for (ReceiveLine line : delivered) {
            Limits.checkQuantity(line.qty(), "Quantity");
            Limits.checkAmount(line.unitCostCents(), "Unit cost");
            if (!seen.add(line.orderLineId())) {
                throw new BusinessException("Each item can only appear once in a delivery");
            }
        }
        return db.inTransaction(c -> {
            OrderRow order = requireOrder(orderId);
            if (order.cancelledAt() != null || order.orderedAt() == null) {
                throw new BusinessException("Only an ordered purchase order can be received");
            }
            Map<Long, LineRow> onOrder = new HashMap<>();
            for (LineRow row : orders.findLines(orderId)) {
                onOrder.put(row.id(), row);
            }
            for (ReceiveLine line : delivered) {
                LineRow row = onOrder.get(line.orderLineId());
                if (row == null) {
                    throw new BusinessException("That item is not on this order");
                }
                int remaining = row.qtyOrdered() - row.qtyReceived();
                if (line.qty() > remaining) {
                    throw new BusinessException("Only " + remaining + " left to receive for " + row.name());
                }
            }

            long receiptId = orders.insertReceipt(orderId, invoice, manager.id(), Instant.now());
            for (ReceiveLine line : delivered) {
                LineRow row = onOrder.get(line.orderLineId());
                Product product = products.findById(row.productId())
                        .orElseThrow(() -> new BusinessException("Product no longer exists"));
                // Read before the stock moves, because the average uses the quantity that was on hand.
                long newCost = averageCost(product.stockQty(), product.costCents(), line.qty(), line.unitCostCents());
                ledger.apply(product.id(), MovementType.RECEIVE, line.qty(), "Received on order " + orderId,
                        manager.id(), null, receiptId);
                orders.insertReceiptLine(receiptId, row.id(), line.qty(), line.unitCostCents());
                if (newCost != product.costCents()) {
                    products.updateCost(product.id(), newCost);
                    audit.record("COST_CHANGE", "product", String.valueOf(product.id()),
                            Money.toPlainAmount(product.costCents()) + " -> " + Money.toPlainAmount(newCost)
                                    + " on order " + orderId);
                }
            }
            audit.record("RECEIVE", "purchase_order", String.valueOf(orderId),
                    delivered.size() + " line(s)" + (invoice == null ? "" : ", invoice " + invoice));
            return receiptId;
        });
    }

    /** Cancels an order. Possible only while nothing has arrived against it. */
    public void cancel(long orderId) {
        session.requireAtLeast(Role.MANAGER);
        db.runInTransaction(() -> {
            OrderRow order = requireOrder(orderId);
            if (order.cancelledAt() != null) {
                throw new BusinessException("This order is already cancelled");
            }
            if (orders.countReceipts(orderId) > 0) {
                throw new BusinessException("Goods have already arrived on this order, so it cannot be cancelled");
            }
            orders.markCancelled(orderId, Instant.now());
            audit.record("PO_CANCEL", "purchase_order", String.valueOf(orderId), order.supplierName());
        });
    }

    /** Orders, newest first. Pass a status to show only orders in that state, or null to show them all. */
    public List<OrderSummary> list(PurchaseStatus only) {
        session.requireAtLeast(Role.MANAGER);
        List<OrderSummary> result = new ArrayList<>();
        for (OrderRow row : orders.findOrders()) {
            OrderSummary summary = summarize(row, orders.findLines(row.id()));
            if (only == null || summary.status() == only) {
                result.add(summary);
            }
        }
        return result;
    }

    public OrderDetail detail(long orderId) {
        session.requireAtLeast(Role.MANAGER);
        OrderRow row = requireOrder(orderId);
        List<LineRow> lines = orders.findLines(orderId);
        List<OrderLine> shown = lines.stream()
                .map(l -> new OrderLine(l.id(), l.productId(), l.sku(), l.name(), l.qtyOrdered(), l.qtyReceived(),
                        l.unitCostCents()))
                .toList();
        return new OrderDetail(summarize(row, lines), shown, orders.countReceipts(orderId));
    }

    /**
     * Stock products at or below their reorder level that are not already on an open order. The quantity is the product's
     * reorder quantity when it has one, otherwise enough to bring it back up to twice its reorder level.
     */
    public List<Suggestion> reorderSuggestions() {
        session.requireAtLeast(Role.MANAGER);
        Set<Long> onOpenOrders = productsOnOpenOrders();
        Map<Long, String> supplierNames = new HashMap<>();
        for (Supplier supplier : catalog.findSuppliers()) {
            supplierNames.put(supplier.id(), supplier.name());
        }
        List<Suggestion> result = new ArrayList<>();
        for (Product product : products.findLowStock()) {
            if (onOpenOrders.contains(product.id())) {
                continue;
            }
            int wanted = product.reorderQty() > 0
                    ? product.reorderQty()
                    : Math.max(1, 2 * product.reorderLevel() - product.stockQty());
            String supplierName = product.supplierId() == null
                    ? NO_SUPPLIER
                    : supplierNames.getOrDefault(product.supplierId(), NO_SUPPLIER);
            result.add(new Suggestion(product.supplierId(), supplierName, product.id(), product.sku(), product.name(),
                    product.stockQty(), Math.min(wanted, Limits.MAX_QUANTITY), product.costCents()));
        }
        result.sort(Comparator.comparing(Suggestion::supplierName).thenComparing(Suggestion::sku));
        return result;
    }

    /**
     * Starts one draft order per supplier for the products that are low on stock. Products with no supplier are left
     * out, because an order needs one. Returns the ids of the new orders.
     */
    public List<Long> draftFromSuggestions() {
        User manager = session.requireAtLeast(Role.MANAGER);
        Map<Long, List<Suggestion>> bySupplier = reorderSuggestions().stream()
                .filter(s -> s.supplierId() != null)
                .collect(Collectors.groupingBy(Suggestion::supplierId, LinkedHashMap::new, Collectors.toList()));
        return db.inTransaction(c -> {
            List<Long> created = new ArrayList<>();
            for (Map.Entry<Long, List<Suggestion>> group : bySupplier.entrySet()) {
                long orderId = orders.insertOrder(group.getKey(), "Reorder suggestions", manager.id(), Instant.now());
                for (Suggestion s : group.getValue()) {
                    orders.insertLine(orderId, s.productId(), s.suggestedQty(), s.unitCostCents());
                }
                audit.record("PO_CREATE", "purchase_order", String.valueOf(orderId), "from reorder suggestions");
                created.add(orderId);
            }
            return created;
        });
    }

    /** Products on a draft, ordered or part-received order. Those are already being bought, so they are not suggested. */
    private Set<Long> productsOnOpenOrders() {
        Set<Long> ids = new HashSet<>();
        for (OrderRow row : orders.findOrders()) {
            List<LineRow> lines = orders.findLines(row.id());
            PurchaseStatus status = summarize(row, lines).status();
            if (status == PurchaseStatus.DRAFT || status == PurchaseStatus.ORDERED || status == PurchaseStatus.PARTIAL) {
                for (LineRow line : lines) {
                    ids.add(line.productId());
                }
            }
        }
        return ids;
    }

    /** An order as CSV, to send to the supplier. */
    public void exportCsv(long orderId, Path file) {
        OrderDetail detail = detail(orderId);
        List<List<?>> rows = new ArrayList<>();
        for (OrderLine line : detail.lines()) {
            rows.add(List.of(
                    new Text(line.sku()),
                    new Text(line.name()),
                    String.valueOf(line.qtyOrdered()),
                    Money.toPlainAmount(line.unitCostCents()),
                    Money.toPlainAmount(line.lineTotalCents())));
        }
        CsvWriter.write(file, List.of("sku", "name", "qty_ordered", "unit_cost", "line_total"), rows);
    }

    /**
     * The average cost once a delivery is in: the stock on hand and the delivery, each at its own cost, averaged and
     * rounded to the cent. With nothing on hand (or a negative count) the delivery's cost simply becomes the new cost.
     */
    static long averageCost(int onHand, long currentCostCents, int receivedQty, long receivedCostCents) {
        if (onHand <= 0) {
            return receivedCostCents;
        }
        long value = Math.addExact(Math.multiplyExact((long) onHand, currentCostCents),
                Math.multiplyExact((long) receivedQty, receivedCostCents));
        return BigDecimal.valueOf(value)
                .divide(BigDecimal.valueOf((long) onHand + receivedQty), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private OrderRow requireOrder(long orderId) {
        return orders.findOrder(orderId)
                .orElseThrow(() -> new BusinessException("That purchase order no longer exists"));
    }

    private static void requireDraft(OrderRow order, String message) {
        if (order.cancelledAt() != null || order.orderedAt() != null) {
            throw new BusinessException(message);
        }
    }

    private static OrderSummary summarize(OrderRow row, List<LineRow> lines) {
        int ordered = 0;
        int received = 0;
        long total = 0;
        boolean allReceived = !lines.isEmpty();
        for (LineRow line : lines) {
            ordered += line.qtyOrdered();
            received += line.qtyReceived();
            total = Math.addExact(total, Math.multiplyExact((long) line.qtyOrdered(), line.unitCostCents()));
            if (line.qtyReceived() < line.qtyOrdered()) {
                allReceived = false;
            }
        }
        return new OrderSummary(row.id(), row.supplierId(), row.supplierName(), row.note(),
                statusOf(row, received, allReceived), row.createdAt(), ordered, received, total);
    }

    static PurchaseStatus statusOf(OrderRow row, int received, boolean allReceived) {
        if (row.cancelledAt() != null) {
            return PurchaseStatus.CANCELLED;
        }
        if (row.orderedAt() == null) {
            return PurchaseStatus.DRAFT;
        }
        if (received == 0) {
            return PurchaseStatus.ORDERED;
        }
        return allReceived ? PurchaseStatus.RECEIVED : PurchaseStatus.PARTIAL;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
