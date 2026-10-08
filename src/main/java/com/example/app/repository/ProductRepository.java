package com.example.app.repository;

import com.example.app.db.Columns;
import com.example.app.db.Database;
import com.example.app.domain.Product;
import com.example.app.domain.ProductDraft;
import com.example.app.domain.ProductKind;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ProductRepository {

    /** Most rows a search returns. The cashier narrows the search rather than scrolling a huge list. */
    public static final int SEARCH_LIMIT = 50;

    private static final String SELECT = "SELECT id, sku, barcode, name, category_id, supplier_id, price_cents, "
            + "cost_cents, tax_rate_bp, stock_qty, reorder_level, reorder_qty, active, kind FROM products";

    private final Database db;

    public ProductRepository(Database db) {
        this.db = db;
    }

    public long insert(ProductDraft d) {
        return db.insert("INSERT INTO products (sku, barcode, name, category_id, supplier_id, price_cents, cost_cents, "
                        + "tax_rate_bp, stock_qty, reorder_level, reorder_qty, active, kind) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)",
                d.sku(), blankToNull(d.barcode()), d.name(), d.categoryId(), d.supplierId(), d.priceCents(),
                d.costCents(), d.taxRateBp(), d.reorderLevel(), d.reorderQty(), d.active(), d.kind());
    }

    public void update(long id, ProductDraft d) {
        db.update("UPDATE products SET sku = ?, barcode = ?, name = ?, category_id = ?, supplier_id = ?, "
                        + "price_cents = ?, cost_cents = ?, tax_rate_bp = ?, reorder_level = ?, reorder_qty = ?, "
                        + "active = ?, kind = ? WHERE id = ?",
                d.sku(), blankToNull(d.barcode()), d.name(), d.categoryId(), d.supplierId(), d.priceCents(),
                d.costCents(), d.taxRateBp(), d.reorderLevel(), d.reorderQty(), d.active(), d.kind(), id);
    }

    public void adjustStock(long id, int delta) {
        db.update("UPDATE products SET stock_qty = stock_qty + ? WHERE id = ?", delta, id);
    }

    /** Only the purchasing service changes a cost, and it writes the change to the audit log. */
    public void updateCost(long id, long costCents) {
        db.update("UPDATE products SET cost_cents = ? WHERE id = ?", costCents, id);
    }

    public Optional<Product> findById(long id) {
        return db.queryOne(SELECT + " WHERE id = ?", ProductRepository::map, id);
    }

    /** Matches an exact barcode, or a SKU ignoring case. Used by the scanner field. */
    public Optional<Product> findByCode(String code) {
        return db.queryOne(SELECT + " WHERE barcode = ? OR sku = ?", ProductRepository::map,
                code, code.toUpperCase(Locale.ROOT));
    }

    /**
     * Name, SKU or barcode contains the text, ignoring case. Wildcards typed by the user are matched literally.
     */
    public List<Product> search(String text) {
        String pattern = "%" + escapeLike(text.toLowerCase(Locale.ROOT)) + "%";
        return db.query(SELECT + " WHERE LOWER(name) LIKE ? ESCAPE '!' OR LOWER(sku) LIKE ? ESCAPE '!' "
                        + "OR LOWER(barcode) LIKE ? ESCAPE '!' ORDER BY name LIMIT ?",
                ProductRepository::map, pattern, pattern, pattern, SEARCH_LIMIT);
    }

    public List<Product> findAll() {
        return db.query(SELECT + " ORDER BY name", ProductRepository::map);
    }

    public List<Product> findLowStock() {
        return db.query(SELECT + " WHERE active = TRUE AND kind = 'STOCK' AND stock_qty <= reorder_level ORDER BY stock_qty",
                ProductRepository::map);
    }

    /** Sum of the cached stock quantities. Compared against the movement history by the data check. */
    public long sumStockQuantities() {
        return db.queryOne("SELECT COALESCE(SUM(stock_qty), 0) FROM products", rs -> rs.getLong(1)).orElse(0L);
    }

    /** Escapes the LIKE wildcards with '!' so typed % and _ match literally. */
    static String escapeLike(String text) {
        return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Product map(ResultSet rs) throws SQLException {
        return new Product(
                rs.getLong("id"),
                rs.getString("sku"),
                rs.getString("barcode"),
                rs.getString("name"),
                Columns.longOrNull(rs, "category_id"),
                Columns.longOrNull(rs, "supplier_id"),
                rs.getLong("price_cents"),
                rs.getLong("cost_cents"),
                rs.getInt("tax_rate_bp"),
                rs.getInt("stock_qty"),
                rs.getInt("reorder_level"),
                rs.getInt("reorder_qty"),
                rs.getBoolean("active"),
                ProductKind.valueOf(rs.getString("kind")));
    }
}
