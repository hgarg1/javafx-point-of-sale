package com.example.app.repository;

import com.example.app.db.Database;
import com.example.app.domain.Category;
import com.example.app.domain.Supplier;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Categories and suppliers, which are small lookup lists for products. */
public final class CatalogRepository {

    private final Database db;

    public CatalogRepository(Database db) {
        this.db = db;
    }

    public long insertCategory(String name) {
        return db.insert("INSERT INTO categories (name) VALUES (?)", name);
    }

    public Optional<Category> findCategoryByName(String name) {
        return db.queryOne("SELECT id, name FROM categories WHERE UPPER(name) = ?",
                rs -> new Category(rs.getLong("id"), rs.getString("name")), upper(name));
    }

    public List<Category> findCategories() {
        return db.query("SELECT id, name FROM categories ORDER BY name",
                rs -> new Category(rs.getLong("id"), rs.getString("name")));
    }

    public long insertSupplier(String name, String contact) {
        return db.insert("INSERT INTO suppliers (name, contact) VALUES (?, ?)", name, contact);
    }

    public Optional<Supplier> findSupplierByName(String name) {
        return db.queryOne("SELECT id, name, contact FROM suppliers WHERE UPPER(name) = ?",
                rs -> new Supplier(rs.getLong("id"), rs.getString("name"), rs.getString("contact")), upper(name));
    }

    public Optional<Supplier> findSupplier(long id) {
        return db.queryOne("SELECT id, name, contact FROM suppliers WHERE id = ?",
                rs -> new Supplier(rs.getLong("id"), rs.getString("name"), rs.getString("contact")), id);
    }

    public List<Supplier> findSuppliers() {
        return db.query("SELECT id, name, contact FROM suppliers ORDER BY name",
                rs -> new Supplier(rs.getLong("id"), rs.getString("name"), rs.getString("contact")));
    }

    private static String upper(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
