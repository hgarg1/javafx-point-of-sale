package com.example.app.service;

import com.example.app.db.Database;
import com.example.app.db.Migrator;
import com.example.app.domain.TenderKind;
import com.example.app.payment.CashProvider;
import com.example.app.payment.ExternalPaymentProvider;
import com.example.app.payment.PaymentProvider;
import com.example.app.payment.SimulatedCardProvider;
import com.example.app.payment.StoredValueProvider;
import com.example.app.repository.AuditRepository;
import com.example.app.repository.CatalogRepository;
import com.example.app.repository.HoldRepository;
import com.example.app.repository.ProductRepository;
import com.example.app.repository.PurchaseRepository;
import com.example.app.repository.RefundRepository;
import com.example.app.repository.SaleRepository;
import com.example.app.repository.SettingsRepository;
import com.example.app.repository.ShiftRepository;
import com.example.app.repository.StockRepository;
import com.example.app.repository.StoredValueRepository;
import com.example.app.repository.TenderRepository;
import com.example.app.repository.UserRepository;
import com.example.app.util.PasswordHasher;

import java.nio.file.Path;
import java.time.Clock;
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
    private final BackupService backups;
    private final SimulatedCardProvider cardProvider;
    private final SettingsService storeSettings;
    private final StoredValueService storedValueService;
    private final HoldService holds;
    private final PurchasingService purchasing;
    private final TenderService tenders;

    /** Without backups. Suitable for tests and in-memory databases. */
    public AppServices(String jdbcUrl) {
        this(jdbcUrl, new PasswordHasher(), null);
    }

    /** Without backups, with a custom hasher. Tests pass a low iteration count to run faster. */
    public AppServices(String jdbcUrl, PasswordHasher hasher) {
        this(jdbcUrl, hasher, null);
    }

    /**
     * @param jdbcUrl      H2 URL, e.g. {@code jdbc:h2:mem:test} for tests or a file URL for the real app.
     *                     Pending migrations run on open.
     * @param backupFolder where backups are written: before an upgrade of existing data, and on exit. Null disables
     *                     backups.
     */
    public AppServices(String jdbcUrl, PasswordHasher hasher, Path backupFolder) {
        this.db = new Database(jdbcUrl);
        this.backups = new BackupService(db, backupFolder, Clock.systemUTC());
        Runnable beforeUpgrade = backupFolder == null ? null : () -> backups.backupNow("before-upgrade");
        Migrator.migrate(db, beforeUpgrade);

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
        this.cardProvider = new SimulatedCardProvider();
        StoredValueService storedValue = new StoredValueService(new StoredValueRepository(db), session);
        this.storedValueService = storedValue;
        // A payment type's kind decides which provider takes its money, so the providers are keyed by kind.
        Map<TenderKind, PaymentProvider> providers = Map.of(
                TenderKind.CASH, new CashProvider(),
                TenderKind.CARD, cardProvider,
                TenderKind.EXTERNAL, new ExternalPaymentProvider(),
                TenderKind.STORED_VALUE, new StoredValueProvider(storedValue));
        TenderRepository tenderRepo = new TenderRepository(db);

        this.auth = new AuthService(db, users, hasher, session, audit, new LoginGuard(Clock.systemUTC()));
        this.inventory = new InventoryService(db, products, catalog, stock, ledger, audit, session);
        this.tenders = new TenderService(db, tenderRepo, shiftRepo, audit, session);
        this.checkout = new CheckoutService(db, products, sales, shiftRepo, users, settings, ledger, providers,
                audit, session, storedValue, tenders);
        this.shifts = new ShiftService(db, shiftRepo, sales, refundRepo, users, audit, session, tenderRepo);
        this.refunds = new RefundService(db, sales, refundRepo, shiftRepo, users, ledger, providers, audit, session,
                storedValue, tenders);
        this.reports = new ReportService(sales, refundRepo, products, users, tenderRepo, session);
        this.holds = new HoldService(db, new HoldRepository(db), products, users, settings, audit, session);
        this.purchasing = new PurchasingService(db, new PurchaseRepository(db), products, catalog, ledger, audit, session);
        this.storeSettings = new SettingsService(db, settings, sales, audit, session);
        storeSettings.applyFormat();
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

    public BackupService backups() {
        return backups;
    }

    public SettingsService storeSettings() {
        return storeSettings;
    }

    public StoredValueService storedValue() {
        return storedValueService;
    }

    /** The payment types the till offers. Anyone can read them; only an administrator changes them. */
    public TenderService tenders() {
        return tenders;
    }

    public HoldService holds() {
        return holds;
    }

    public PurchasingService purchasing() {
        return purchasing;
    }

    /** The card provider in use. Exposed so tests can check that no card hold outlives a failed sale. */
    public SimulatedCardProvider cardProvider() {
        return cardProvider;
    }

    /** Raw database access for tests that check what was actually stored. Not part of the app's API. */
    Database database() {
        return db;
    }

    public String storeName() {
        return settings.find("store_name").orElse("Store");
    }

    @Override
    public void close() {
        db.close();
    }
}
