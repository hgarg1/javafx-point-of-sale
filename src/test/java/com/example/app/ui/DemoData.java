package com.example.app.ui;

import com.example.app.domain.MovementType;
import com.example.app.domain.Product;
import com.example.app.domain.ProductDraft;
import com.example.app.domain.Role;
import com.example.app.domain.Sale;
import com.example.app.domain.TenderCodes;
import com.example.app.service.AppServices;
import com.example.app.service.CheckoutService.CartItem;
import com.example.app.service.CheckoutService.PaymentRequest;
import com.example.app.service.PurchasingService.ReceiveLine;
import com.example.app.service.RefundService.RefundLineRequest;

import java.util.List;

/**
 * A believable day of trading for screenshots and manual testing. Uses only the public service API, so it also
 * works as an end-to-end script. All names are invented.
 */
final class DemoData {

    private static final String MANAGER = "maria";
    private static final String MANAGER_PASSWORD = "maria-pass-1";
    private static final String CASHIER = "sam";
    private static final String CASHIER_PASSWORD = "sam-pass-1";

    /** One catalogue row. Prices in cents, tax in basis points, {@code category} and {@code supplier} are ids. */
    private record Item(String sku, String name, long category, Long supplier, long price, long cost, int taxBp,
                        int stock, int reorder) {
    }

    private DemoData() {
    }

    static void seed(AppServices app) {
        app.auth().completeSetup("owner", "owner-pass-1");
        provision(app, MANAGER, MANAGER_PASSWORD, Role.MANAGER);
        provision(app, CASHIER, CASHIER_PASSWORD, Role.CASHIER);
        app.auth().login("owner", "owner-pass-1");
        long coffee = app.inventory().addCategory("Coffee");
        long bakery = app.inventory().addCategory("Bakery");
        long grocery = app.inventory().addCategory("Groceries");
        long household = app.inventory().addCategory("Household");
        long roaster = app.inventory().addSupplier("Northwind Roasters", "orders@northwind.example");
        long farm = app.inventory().addSupplier("Harbor Farm Co-op", "sales@harbor.example");
        long wholesale = app.inventory().addSupplier("Acme Wholesale", null);

        List<Item> catalogue = List.of(
                new Item("COF-ESP-250", "Espresso Beans 250g", coffee, roaster, 1_450, 610, 0, 12, 20),
                new Item("COF-HOU-500", "House Blend 500g", coffee, roaster, 1_890, 820, 0, 8, 15),
                new Item("COF-DEC-250", "Decaf Medium 250g", coffee, roaster, 1_390, 590, 0, 4, 10),
                new Item("COF-CAP-001", "Cappuccino Cup", coffee, null, 450, 90, 0, 60, 20),
                new Item("COF-LAT-001", "Latte Cup", coffee, null, 475, 95, 0, 40, 20),
                new Item("COF-FIL-100", "Paper Filters (100)", coffee, roaster, 420, 180, 0, 25, 10),
                new Item("BAK-CRO-001", "Butter Croissant", bakery, farm, 325, 110, 800, 18, 12),
                new Item("BAK-SOU-001", "Sourdough Loaf", bakery, farm, 650, 240, 800, 9, 8),
                new Item("BAK-MUF-001", "Blueberry Muffin", bakery, farm, 350, 120, 800, 0, 6),
                new Item("BAK-COO-006", "Chocolate Chip Cookie", bakery, farm, 225, 70, 800, 30, 10),
                new Item("BAK-BAG-001", "Bagel Everything", bakery, farm, 275, 90, 800, 16, 10),
                new Item("GRO-MLK-1L", "Whole Milk 1L", grocery, farm, 289, 175, 0, 22, 12),
                new Item("GRO-EGG-12", "Free Range Eggs (12)", grocery, farm, 549, 350, 0, 3, 6),
                new Item("GRO-HNY-340", "Wildflower Honey 340g", grocery, farm, 699, 380, 800, 7, 4),
                new Item("GRO-OAT-500", "Rolled Oats 500g", grocery, wholesale, 399, 190, 0, 15, 8),
                new Item("GRO-PAS-500", "Spaghetti 500g", grocery, wholesale, 249, 105, 0, 26, 10),
                new Item("GRO-JAM-300", "Strawberry Jam 300g", grocery, farm, 425, 210, 800, 11, 5),
                new Item("GRO-OLJ-750", "Extra Virgin Olive Oil 750ml", grocery, wholesale, 1_299, 760, 0, 5, 4),
                new Item("GRO-TEA-020", "Green Tea (20 bags)", grocery, wholesale, 510, 260, 0, 9, 6),
                new Item("GRO-SUG-1KG", "Cane Sugar 1kg", grocery, wholesale, 310, 150, 0, 12, 6),
                new Item("HOU-PAP-004", "Paper Towels (4 rolls)", household, wholesale, 799, 480, 800, 14, 6),
                new Item("HOU-DSH-001", "Dish Soap 750ml", household, wholesale, 399, 185, 800, 19, 8),
                new Item("HOU-SPG-003", "Kitchen Sponges (3)", household, wholesale, 349, 140, 800, 2, 5),
                new Item("HOU-BAG-050", "Compost Bags (50)", household, wholesale, 1_199, 700, 800, 6, 4),
                new Item("HOU-CLN-500", "Glass Cleaner 500ml", household, wholesale, 499, 230, 800, 0, 3));
        for (Item item : catalogue) {
            ProductDraft draft = new ProductDraft(item.sku(), null, item.name(), item.category(), item.supplier(),
                    item.price(), item.cost(), item.taxBp(), item.reorder(), true);
            app.inventory().createProduct(draft, item.stock());
        }
        app.auth().logout();

        // Sam works the till; Maria opens her own shift later to process a refund.
        app.auth().login(CASHIER, CASHIER_PASSWORD);
        app.shifts().openShift(20_000);
        List<Product> products = app.inventory().listProducts();
        sell(app, products, "COF-ESP-250", 2, 0, TenderCodes.CARD, "4111111111111111");
        sell(app, products, "BAK-CRO-001", 3, 0, TenderCodes.CASH, null);
        sell(app, products, "GRO-MLK-1L", 1, 0, TenderCodes.CASH, null);
        sell(app, products, "COF-CAP-001", 2, 1_000, TenderCodes.CASH, null);
        sell(app, products, "HOU-DSH-001", 1, 0, TenderCodes.CARD, "5555555555554444");
        sell(app, products, "GRO-HNY-340", 1, 0, TenderCodes.CASH, null);
        app.auth().logout();

        // Maria approves a refund on Sam's open shift, and logs a breakage.
        app.auth().login(MANAGER, MANAGER_PASSWORD);
        Sale firstSale = app.refunds().findSaleByReceipt("R001000").orElseThrow();
        long lineId = app.refunds().refundableLines(firstSale.id()).get(0).line().id();
        app.refunds().refund(firstSale.id(), List.of(new RefundLineRequest(lineId, 1)),
                TenderCodes.CARD, "Customer changed their mind", true, null);
        app.inventory().recordMovement(productId(app.inventory().listProducts(), "GRO-EGG-12"),
                MovementType.WASTE, 1, "cracked in transit");
        app.auth().logout();

        // A sale held for a customer who stepped away, and purchase orders in three states.
        app.auth().login(CASHIER, CASHIER_PASSWORD);
        app.holds().hold(List.of(new CartItem(productId(products, "BAK-CRO-001"), 2, 0)), "Table 4");
        app.auth().logout();

        app.auth().login(MANAGER, MANAGER_PASSWORD);
        long beans = app.purchasing().createDraft(roaster, "Weekly beans");
        long beanLine = app.purchasing().addLine(beans, productId(products, "COF-ESP-250"), 20, 900);
        app.purchasing().order(beans);
        app.purchasing().receive(beans, "NW-7731", List.of(new ReceiveLine(beanLine, 12, 900)));
        long dairy = app.purchasing().createDraft(farm, "Dairy top-up");
        app.purchasing().addLine(dairy, productId(products, "GRO-MLK-1L"), 24, 120);
        app.purchasing().createDraft(wholesale, null);
        app.auth().logout();
    }

    /** Creates an account the way an administrator does, then completes the forced first-login change. */
    private static void provision(AppServices app, String username, String finalPassword, Role role) {
        app.auth().login("owner", "owner-pass-1");
        app.auth().createUser(username, "temporary-1", role);
        app.auth().logout();
        app.auth().login(username, "temporary-1");
        app.auth().changeOwnPassword("temporary-1", finalPassword);
        app.auth().logout();
    }

    /** Sells one line, paying with a tender twice the line total so change is due. */
    private static void sell(AppServices app, List<Product> products, String sku, int qty, int discountBp,
                             String method, String cardNumber) {
        long productId = productId(products, sku);
        long unitPrice = products.stream().filter(p -> p.id() == productId).findFirst().orElseThrow().priceCents();
        Long approver = discountBp > 1_000 ? app.auth().verifyApprover(MANAGER, MANAGER_PASSWORD).id() : null;
        app.checkout().checkout(
                List.of(new CartItem(productId, qty, discountBp)),
                List.of(new PaymentRequest(method, unitPrice * qty * 2L, cardNumber)),
                approver);
    }

    private static long productId(List<Product> products, String sku) {
        return products.stream().filter(p -> p.sku().equals(sku)).findFirst().orElseThrow().id();
    }
}
