package com.example.app.service;

import com.example.app.db.Database;
import com.example.app.domain.MoneyFormat;
import com.example.app.domain.Role;
import com.example.app.error.BusinessException;
import com.example.app.repository.SaleRepository;
import com.example.app.repository.SettingsRepository;

import java.util.Optional;

/** The store's settings: name, currency and locale, discount rules, negative stock and the receipt footer. */
public final class SettingsService {

    public record StoreSettings(String storeName, String currency, String locale, int discountApprovalBp,
                                boolean allowNegativeStock, String receiptFooter) {
    }

    static final String DEFAULT_CURRENCY = "USD";
    static final String DEFAULT_LOCALE = "en-US";
    static final String DEFAULT_FOOTER = "Thank you for shopping with us";

    private final Database db;
    private final SettingsRepository settings;
    private final SaleRepository sales;
    private final AuditService audit;
    private final Session session;

    SettingsService(Database db, SettingsRepository settings, SaleRepository sales, AuditService audit,
                    Session session) {
        this.db = db;
        this.settings = settings;
        this.sales = sales;
        this.audit = audit;
        this.session = session;
    }

    public StoreSettings current() {
        session.requireUser();
        return read();
    }

    /** Saves the settings (administrators only). The currency is locked once the first sale has been made. */
    public void update(StoreSettings input) {
        session.requireAtLeast(Role.ADMIN);
        String name = input.storeName() == null ? "" : input.storeName().trim();
        if (name.isEmpty() || name.length() > 60) {
            throw new BusinessException("Store name must be between 1 and 60 characters");
        }
        if (input.discountApprovalBp() < 0 || input.discountApprovalBp() > 10_000) {
            throw new BusinessException("The discount approval level must be between 0% and 100%");
        }
        String footer = input.receiptFooter() == null ? "" : input.receiptFooter().trim();
        if (footer.length() > 200) {
            throw new BusinessException("The receipt footer must be 200 characters or fewer");
        }
        StoreSettings clean = new StoreSettings(name, input.currency(), input.locale(), input.discountApprovalBp(),
                input.allowNegativeStock(), footer);

        db.inTransaction(c -> {
            if (!read().currency().equals(clean.currency()) && sales.count() > 0) {
                throw new BusinessException("The currency cannot be changed after the first sale");
            }
            settings.put("store_name", clean.storeName());
            settings.put("currency", clean.currency());
            settings.put("locale", clean.locale());
            settings.put("discount_approval_threshold_bp", String.valueOf(clean.discountApprovalBp()));
            settings.put("allow_negative_stock", String.valueOf(clean.allowNegativeStock()));
            settings.put("receipt_footer", clean.receiptFooter());
            audit.record("SETTINGS", "store", null, clean.storeName() + ", " + clean.currency() + ", " + clean.locale());
            return null;
        });
        MoneyFormat.configure(clean.currency(), clean.locale());
    }

    /** Applies the saved currency and locale to the formatting used throughout the app. No login needed. */
    void applyFormat() {
        StoreSettings saved = read();
        try {
            MoneyFormat.configure(saved.currency(), saved.locale());
        } catch (BusinessException e) {
            MoneyFormat.configure(DEFAULT_CURRENCY, DEFAULT_LOCALE);
        }
    }

    StoreSettings read() {
        return new StoreSettings(
                settings.find("store_name").orElse("Store"),
                settings.find("currency").orElse(DEFAULT_CURRENCY),
                settings.find("locale").orElse(DEFAULT_LOCALE),
                settings.find("discount_approval_threshold_bp").map(Integer::parseInt).orElse(1000),
                Boolean.parseBoolean(settings.find("allow_negative_stock").orElse("false")),
                settings.find("receipt_footer").orElse(DEFAULT_FOOTER));
    }
}
