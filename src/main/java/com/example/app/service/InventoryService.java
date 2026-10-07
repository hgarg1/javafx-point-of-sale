package com.example.app.service;

import com.example.app.db.Database;
import com.example.app.domain.Category;
import com.example.app.domain.MovementType;
import com.example.app.domain.Money;
import com.example.app.domain.Product;
import com.example.app.domain.ProductDraft;
import com.example.app.domain.Role;
import com.example.app.domain.StockMovement;
import com.example.app.domain.Supplier;
import com.example.app.domain.User;
import com.example.app.error.BusinessException;
import com.example.app.repository.CatalogRepository;
import com.example.app.repository.ProductRepository;
import com.example.app.repository.StockRepository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class InventoryService {

    private final Database db;
    private final ProductRepository products;
    private final CatalogRepository catalog;
    private final StockRepository movements;
    private final StockLedger ledger;
    private final AuditService audit;
    private final Session session;

    InventoryService(Database db, ProductRepository products, CatalogRepository catalog, StockRepository movements,
                     StockLedger ledger, AuditService audit, Session session) {
        this.db = db;
        this.products = products;
        this.catalog = catalog;
        this.movements = movements;
        this.ledger = ledger;
        this.audit = audit;
        this.session = session;
    }

    // ---- reads (any logged-in user, so cashiers can look up products) ----

    public List<Product> listProducts() {
        session.requireUser();
        return products.findAll();
    }

    public List<Product> search(String text) {
        session.requireUser();
        return products.search(text.trim());
    }

    /** Finds a product by exact barcode or SKU, as a scanner would. */
    public Optional<Product> findByCode(String code) {
        session.requireUser();
        return products.findByCode(code.trim());
    }

    public Optional<Product> findProduct(long id) {
        session.requireUser();
        return products.findById(id);
    }

    public List<Product> listLowStock() {
        session.requireAtLeast(Role.MANAGER);
        return products.findLowStock();
    }

    public List<StockMovement> movementsFor(long productId) {
        session.requireAtLeast(Role.MANAGER);
        return movements.findByProduct(productId);
    }

    public List<Category> listCategories() {
        session.requireUser();
        return catalog.findCategories();
    }

    public List<Supplier> listSuppliers() {
        session.requireUser();
        return catalog.findSuppliers();
    }

    // ---- product maintenance (manager) ----

    public long createProduct(ProductDraft draft, int initialStock) {
        User manager = session.requireAtLeast(Role.MANAGER);
        validate(draft);
        if (initialStock < 0) {
            throw new BusinessException("Initial stock cannot be negative");
        }
        return db.inTransaction(c -> {
            ensureCodesFree(draft, null);
            long id = products.insert(draft);
            audit.record("CREATE", "product", String.valueOf(id), draft.name());
            if (initialStock > 0) {
                ledger.apply(id, MovementType.RECEIVE, initialStock, "Initial stock", manager.id(), null);
            }
            return id;
        });
    }

    public void updateProduct(long id, ProductDraft draft) {
        session.requireAtLeast(Role.MANAGER);
        validate(draft);
        db.runInTransaction(() -> {
            Product existing = products.findById(id)
                    .orElseThrow(() -> new BusinessException("Product no longer exists"));
            ensureCodesFree(draft, id);
            products.update(id, draft);
            audit.record("UPDATE", "product", String.valueOf(id), draft.name());
            if (existing.priceCents() != draft.priceCents()) {
                audit.record("PRICE_CHANGE", "product", String.valueOf(id),
                        Money.toPlainAmount(existing.priceCents()) + " -> " + Money.toPlainAmount(draft.priceCents()));
            }
        });
    }

    public long addCategory(String name) {
        session.requireAtLeast(Role.MANAGER);
        String clean = requireText(name, "Category name");
        return catalog.insertCategory(clean);
    }

    public long addSupplier(String name, String contact) {
        session.requireAtLeast(Role.MANAGER);
        String clean = requireText(name, "Supplier name");
        return catalog.insertSupplier(clean, contact == null || contact.isBlank() ? null : contact.trim());
    }

    // ---- stock (manager) ----

    /**
     * Records a receipt (positive), waste (positive quantity, stored negative) or adjustment (signed).
     * Adjustments and waste need a reason.
     */
    public void recordMovement(long productId, MovementType type, int quantity, String reason) {
        User manager = session.requireAtLeast(Role.MANAGER);
        int delta = switch (type) {
            case RECEIVE -> {
                requirePositive(quantity);
                yield quantity;
            }
            case WASTE -> {
                requirePositive(quantity);
                requireReason(reason);
                yield -quantity;
            }
            case ADJUST -> {
                if (quantity == 0) {
                    throw new BusinessException("Adjustment cannot be zero");
                }
                requireReason(reason);
                yield quantity;
            }
            case SALE, REFUND_RETURN -> throw new BusinessException(
                    "Sales and refunds are recorded from the checkout and refund screens");
        };
        db.runInTransaction(() -> {
            if (products.findById(productId).isEmpty()) {
                throw new BusinessException("Product no longer exists");
            }
            ledger.apply(productId, type, delta, blankToNull(reason), manager.id(), null);
        });
    }

    /** Records an ADJUST for every product whose counted quantity differs from the system quantity. */
    public int stockTake(Map<Long, Integer> countedByProductId) {
        User manager = session.requireAtLeast(Role.MANAGER);
        return db.inTransaction(c -> {
            int adjusted = 0;
            for (Map.Entry<Long, Integer> entry : countedByProductId.entrySet()) {
                Product product = products.findById(entry.getKey())
                        .orElseThrow(() -> new BusinessException("Product no longer exists"));
                int diff = entry.getValue() - product.stockQty();
                if (diff != 0) {
                    ledger.apply(product.id(), MovementType.ADJUST, diff, "Stock take", manager.id(), null);
                    adjusted++;
                }
            }
            audit.record("STOCK_TAKE", "product", null, adjusted + " product(s) adjusted");
            return adjusted;
        });
    }

    // ---- helpers ----

    /** Fails if the SKU or barcode belongs to a different product. {@code ownId} is null for new products. */
    private void ensureCodesFree(ProductDraft draft, Long ownId) {
        products.findByCode(draft.sku()).filter(p -> !Objects.equals(p.id(), ownId)).ifPresent(p -> {
            throw new BusinessException("SKU " + draft.sku() + " is already used by " + p.name());
        });
        if (draft.barcode() != null && !draft.barcode().isBlank()) {
            products.findByCode(draft.barcode().trim()).filter(p -> !Objects.equals(p.id(), ownId)).ifPresent(p -> {
                throw new BusinessException("Barcode " + draft.barcode() + " is already used by " + p.name());
            });
        }
    }

    private static void validate(ProductDraft d) {
        requireText(d.sku(), "SKU");
        requireText(d.name(), "Name");
        if (d.priceCents() < 0 || d.costCents() < 0) {
            throw new BusinessException("Price and cost cannot be negative");
        }
        if (d.taxRateBp() < 0 || d.taxRateBp() > 10_000) {
            throw new BusinessException("Tax rate must be between 0% and 100%");
        }
        if (d.reorderLevel() < 0) {
            throw new BusinessException("Reorder level cannot be negative");
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(label + " is required");
        }
        return value.trim();
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new BusinessException("Quantity must be greater than zero");
        }
    }

    private static void requireReason(String reason) {
        requireText(reason, "Reason");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
