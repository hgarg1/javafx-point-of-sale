package com.example.app.repository;

import com.example.app.db.Database;

import java.util.Optional;

public final class SettingsRepository {

    private final Database db;

    public SettingsRepository(Database db) {
        this.db = db;
    }

    public Optional<String> find(String key) {
        return db.queryOne("SELECT setting_value FROM settings WHERE setting_key = ?", rs -> rs.getString(1), key);
    }

    public void put(String key, String value) {
        int updated = db.update("UPDATE settings SET setting_value = ? WHERE setting_key = ?", value, key);
        if (updated == 0) {
            db.update("INSERT INTO settings (setting_key, setting_value) VALUES (?, ?)", key, value);
        }
    }
}
