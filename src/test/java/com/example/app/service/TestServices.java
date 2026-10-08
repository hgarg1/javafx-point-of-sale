package com.example.app.service;

import com.example.app.domain.Product;
import com.example.app.domain.ProductDraft;
import com.example.app.domain.Role;
import com.example.app.domain.Shift;
import com.example.app.domain.TenderCodes;
import com.example.app.domain.TenderTotal;
import com.example.app.service.CheckoutService.CartItem;
import com.example.app.service.CheckoutService.PaymentRequest;
import com.example.app.service.CheckoutService.SaleDetails;
import com.example.app.util.PasswordHasher;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds fresh in-memory services with the standard accounts, and small helpers shared by the rule tests. */
final class TestServices {

    static final String OWNER = "owner";
    static final String OWNER_PASSWORD = "owner-pass-1";
    static final String MANAGER = "maria";
    static final String MANAGER_PASSWORD = "maria-pass-1";
    static final String CASHIER = "sam";
    static final String CASHIER_PASSWORD = "sam-pass-1";
    static final String TEMPORARY = "temporary-1";

    private TestServices() {
    }

    /** A new, empty database with an owner, a manager and a cashier. Nobody is logged in afterwards. */
    static AppServices fresh() {
        AppServices app = new AppServices(url(), new PasswordHasher(1_000));
        app.auth().completeSetup(OWNER, OWNER_PASSWORD);
        provision(app, MANAGER, MANAGER_PASSWORD, Role.MANAGER);
        provision(app, CASHIER, CASHIER_PASSWORD, Role.CASHIER);
        return app;
    }

    /** A unique in-memory database that stays alive until the JVM exits. H2 cannot BACKUP these. */
    static String url() {
        return "jdbc:h2:mem:" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
    }

    /** A file database in {@code dir}, the same kind the real app uses. Needed for backup tests. */
    static String fileUrl(java.nio.file.Path dir, String name) {
        return "jdbc:h2:file:" + dir.resolve(name).toAbsolutePath().toString().replace('\\', '/');
    }

    /**
     * Creates an account the way an administrator does (temporary password), then completes the forced first-login
     * change to {@code finalPassword}. Leaves nobody logged in.
     */
    static void provision(AppServices app, String username, String finalPassword, Role role) {
        app.auth().login(OWNER, OWNER_PASSWORD);
        app.auth().createUser(username, TEMPORARY, role);
        app.auth().logout();
        app.auth().login(username, TEMPORARY);
        app.auth().changeOwnPassword(TEMPORARY, finalPassword);
        app.auth().logout();
    }

    /** Creates a product as the manager and returns its id. Cost is half the price. The manager stays logged in. */
    static long product(AppServices app, String sku, long priceCents, int taxBp, int stock) {
        app.auth().login(MANAGER, MANAGER_PASSWORD);
        ProductDraft draft = new ProductDraft(sku, null, "Product " + sku, null, null,
                priceCents, priceCents / 2, taxBp, 2, true);
        return app.inventory().createProduct(draft, stock);
    }

    static void login(AppServices app, String username, String password) {
        app.auth().login(username, password);
    }

    /** Logs the cashier in and opens the terminal's shift with the given float. */
    static Shift cashierOpensShift(AppServices app, long floatCents) {
        app.auth().login(CASHIER, CASHIER_PASSWORD);
        return app.shifts().openShift(floatCents);
    }

    static long stockOf(AppServices app, long productId) {
        return app.inventory().findProduct(productId).orElseThrow().stockQty();
    }

    static Product productNamed(AppServices app, long productId) {
        return app.inventory().findProduct(productId).orElseThrow();
    }

    static SaleDetails sellCash(AppServices app, long productId, int qty, int discountBp, long tenderedCents) {
        return app.checkout().checkout(
                List.of(new CartItem(productId, qty, discountBp)),
                List.of(new PaymentRequest(TenderCodes.CASH, tenderedCents, null)),
                null);
    }

    static long managerId(AppServices app) {
        return app.auth().verifyApprover(MANAGER, MANAGER_PASSWORD).id();
    }

    /** What is still refundable on a sale, keyed by tender code. Needs a logged-in user. */
    static Map<String, Long> leftByTender(AppServices app, long saleId) {
        Map<String, Long> left = new HashMap<>();
        for (TenderTotal total : app.refunds().refundableByTender(saleId)) {
            left.put(total.tender(), total.cents());
        }
        return left;
    }

    /** Looks up an account's id as the owner. Leaves nobody logged in. */
    static long idOf(AppServices app, String username) {
        app.auth().login(OWNER, OWNER_PASSWORD);
        long id = app.auth().listUsers().stream()
                .filter(u -> u.username().equals(username))
                .findFirst().orElseThrow().id();
        app.auth().logout();
        return id;
    }
}
