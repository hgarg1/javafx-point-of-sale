package com.example.app.service;

import com.example.app.db.Database;
import com.example.app.db.Migrator;
import com.example.app.domain.PaymentMethod;
import com.example.app.payment.CashProvider;
import com.example.app.payment.PaymentProvider;
import com.example.app.payment.SimulatedCardProvider;
import com.example.app.repository.AuditRepository;
import com.example.app.repository.CatalogRepository;
import com.example.app.repository.ProductRepository;
import com.example.app.repository.RefundRepository;
import com.example.app.repository.SaleRepository;
import com.example.app.repository.SettingsRepository;
import com.example.app.repository.ShiftRepository;
import com.example.app.repository.StockRepository;
import com.example.app.repository.UserRepository;
import com.example.app.util.PasswordHasher;

import java.util.Map;

/** Builds the database connection and every service. Created once at startup. */
public final class AppServices implements AutoCloseable {

    private final Database db;
    private final SettingsRepository settings;
    private final Session session;
    private final AuthService auth;
    private final InventoryService inventory;
    private final CheckoutService checkout;
    private final ShiftService shifts;
    private final RefundService refunds;
    private final ReportService reports;
    private final AuditService audit;

    /**
     * @param jdbcUrl H2 URL, e.g. {@code jdbc:h2:mem:test} for tests or a file URL for the real app.
     *                Migrations run and the first admin account is created if needed.
     */
    public AppServices(String jdbcUrl) {
        this.db = new Database(jdbcUrl);
        Migrator.migrate(db);

        this.session = new Session();
        UserRepository users = new UserRepository(db);
        ProductRepository products = new ProductRepository(db);
        CatalogRepository catalog = new CatalogRepository(db);
        StockRepository stock = new StockRepository(db);
        SaleRepository sales = new SaleRepository(db);
        RefundRepository refundRepo = new RefundRepository(db);
        ShiftRepository shiftRepo = new ShiftRepository(db);
        this.settings = new SettingsRepository(db);
        AuditRepository auditRepo = new AuditRepository(db);

        this.audit = new AuditService(auditRepo, session);
        StockLedger ledger = new StockLedger(products, stock);
        Map<PaymentMethod, PaymentProvider> providers = Map.of(
                PaymentMethod.CASH, new CashProvider(),
                PaymentMethod.CARD, new SimulatedCardProvider());

        this.auth = new AuthService(db, users, new PasswordHasher(), session, audit);
        this.inventory = new InventoryService(db, products, catalog, stock, ledger, audit, session);
        this.checkout = new CheckoutService(db, products, sales, shiftRepo, users, settings, ledger, providers,
                audit, session);
        this.shifts = new ShiftService(db, shiftRepo, sales, refundRepo, audit, session);
        this.refunds = new RefundService(db, sales, refundRepo, shiftRepo, ledger, audit, session);
        this.reports = new ReportService(sales, refundRepo, products, session);

        auth.ensureBootstrapAdmin();
    }

    public Session session() {
        return session;
    }

    public AuthService auth() {
        return auth;
    }

    public InventoryService inventory() {
        return inventory;
    }

    public CheckoutService checkout() {
        return checkout;
    }

    public ShiftService shifts() {
        return shifts;
    }

    public RefundService refunds() {
        return refunds;
    }

    public ReportService reports() {
        return reports;
    }

    public AuditService audit() {
        return audit;
    }

    public String storeName() {
        return settings.find("store_name").orElse("Store");
    }

    @Override
    public void close() {
        db.close();
    }
}
