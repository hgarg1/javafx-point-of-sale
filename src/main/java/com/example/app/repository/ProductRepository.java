package com.example.app.repository;

import com.example.app.db.Columns;
import com.example.app.db.Database;
import com.example.app.domain.Product;
import com.example.app.domain.ProductDraft;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public final class ProductRepository {

    private static final String SELECT = "SELECT id, sku, barcode, name, category_id, supplier_id, price_cents, "
            + "cost_cents, tax_rate_bp, stock_qty, reorder_level, active FROM products";

    private final Database db;

    public ProductRepository(Database db) {
        this.db = db;
    }

    public long insert(ProductDraft d) {
        return db.insert("INSERT INTO products (sku, barcode, name, category_id, supplier_id, price_cents, cost_cents, "
                        + "tax_rate_bp, stock_qty, reorder_level, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)",
                d.sku(), blankToNull(d.barcode()), d.name(), d.categoryId(), d.supplierId(), d.priceCents(),
                d.costCents(), d.taxRateBp(), d.reorderLevel(), d.active());
    }

    public void update(long id, ProductDraft d) {
        db.update("UPDATE products SET sku = ?, barcode = ?, name = ?, category_id = ?, supplier_id = ?, "
                        + "price_cents = ?, cost_cents = ?, tax_rate_bp = ?, reorder_level = ?, active = ? WHERE id = ?",
                d.sku(), blankToNull(d.barcode()), d.name(), d.categoryId(), d.supplierId(), d.priceCents(),
                d.costCents(), d.taxRateBp(), d.reorderLevel(), d.active(), id);
    }

    public void adjustStock(long id, int delta) {
        db.update("UPDATE products SET stock_qty = stock_qty + ? WHERE id = ?", delta, id);
    }

    public Optional<Product> findById(long id) {
        return db.queryOne(SELECT + " WHERE id = ?", ProductRepository::map, id);
    }

    /** Matches an exact barcode or SKU. Used by the scanner field. */
    public Optional<Product> findByCode(String code) {
        return db.queryOne(SELECT + " WHERE barcode = ? OR sku = ?", ProductRepository::map, code, code);
    }

    public List<Product> search(String text) {
        String pattern = "%" + text.toLowerCase() + "%";
        return db.query(SELECT + " WHERE LOWER(name) LIKE ? OR LOWER(sku) LIKE ? OR LOWER(barcode) LIKE ? ORDER BY name",
                ProductRepository::map, pattern, pattern, pattern);
    }

    public List<Product> findAll() {
        return db.query(SELECT + " ORDER BY name", ProductRepository::map);
    }

    public List<Product> findLowStock() {
        return db.query(SELECT + " WHERE active = TRUE AND stock_qty <= reorder_level ORDER BY stock_qty",
                ProductRepository::map);
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
                rs.getBoolean("active"));
    }
}
