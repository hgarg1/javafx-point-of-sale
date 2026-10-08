package com.example.app.ui;

import com.example.app.domain.Role;
import com.example.app.domain.Shift;
import com.example.app.domain.User;
import com.example.app.service.AppServices;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The main window after login: a header (store, shift state, user), a sidebar with the screens this role may use, and
 * the active screen. Screens are built once and kept, so a sale in progress survives a visit elsewhere; each screen
 * refreshes when it is shown. Ctrl+1 to Ctrl+9 switch screens and F11 toggles full screen.
 */
public final class AppShell extends StackPane {

    private static final String SHORTCUTS = """
            Checkout
              F2   Focus the scan / search box
              F6   Hold this sale (park it)
              F7   Recall a held sale
              F9   Take payment

            Anywhere
              Ctrl+1 to Ctrl+9   Switch screen (in sidebar order)
              F11                Full screen
              F1                 This list""";

    private record NavItem(String name, Supplier<Node> factory, Button button) {
    }

    private final AppServices services;
    private final User user;
    private final Runnable onLogout;
    private final BorderPane layout = new BorderPane();
    private final Label shiftPill = new Label();
    private final Label heldPill = new Label();
    private final Map<String, Node> screens = new HashMap<>();
    private final List<NavItem> navItems = new ArrayList<>();
    private final VBox sidebar = new VBox(4);

    public AppShell(AppServices services, UiPrefs prefs, Runnable onLogout) {
        this.services = services;
        this.user = services.session().requireUser();
        this.onLogout = onLogout;
        getChildren().add(layout);
        Toast.attach(this);

        Label store = new Label(services.storeName());
        store.getStyleClass().add("title");
        shiftPill.getStyleClass().add("pill");
        heldPill.getStyleClass().add("pill");
        Label who = Ui.muted(user.username() + " (" + roleLabel(user.role()) + ")");
        Button logout = new Button("Log out", Icons.icon("logout"));
        logout.setOnAction(e -> Ui.attempt(this::logOut));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, store, shiftPill, heldPill, spacer, who, logout);
        header.getStyleClass().add("header");
        header.setAlignment(Pos.CENTER_LEFT);
        layout.setTop(header);

        addNav("Checkout", "cart", () -> new CheckoutView(services, this::refreshHeldPill), Role.CASHIER);
        addNav("Shift", "clock", () -> new ShiftView(services), Role.CASHIER);
        addNav("Refunds", "refund", () -> new RefundView(services), Role.CASHIER);
        addNav("Products", "products", () -> new ProductsView(services), Role.MANAGER);
        addNav("Stock", "stock", () -> new StockView(services), Role.MANAGER);
        addNav("Purchasing", "truck", () -> new PurchasingView(services), Role.MANAGER);
        addNav("Reports", "reports", () -> new ReportsView(services), Role.MANAGER);
        addNav("Users", "users", () -> new UsersView(services), Role.ADMIN);
        addNav("Settings", "settings", () -> new SettingsView(services, prefs), Role.ADMIN);
        sidebar.getStyleClass().add("sidebar");
        layout.setLeft(sidebar);

        setOnKeyPressed(e -> {
            KeyCode code = e.getCode();
            if (code == KeyCode.F11) {
                Stage stage = (Stage) getScene().getWindow();
                stage.setFullScreen(!stage.isFullScreen());
            } else if (code == KeyCode.F1) {
                Ui.showInfo(SHORTCUTS);
            } else if (e.isShortcutDown() && code.isDigitKey() && code.getChar().matches("[1-9]")) {
                int index = Integer.parseInt(code.getChar()) - 1;
                if (index < navItems.size()) {
                    navItems.get(index).button().fire();
                }
            }
        });

        // The idle lock watches this whole stack, so it covers the header and sidebar as well as the screens.
        new IdleLock(services, this, prefs.idleLimit()).start();

        navItems.get(0).button().fire();
    }

    private void addNav(String name, String icon, Supplier<Node> factory, Role minimum) {
        if (!user.role().atLeast(minimum)) {
            return;
        }
        Button button = new Button(name, Icons.icon(icon));
        button.getStyleClass().add("nav-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setTooltip(new Tooltip("Ctrl+" + (navItems.size() + 1)));
        NavItem item = new NavItem(name, factory, button);
        navItems.add(item);
        button.setOnAction(e -> Ui.attempt(() -> show(item)));
        sidebar.getChildren().add(button);
    }

    private void show(NavItem item) {
        Node screen = screens.computeIfAbsent(item.name(), n -> item.factory().get());
        if (screen instanceof Screen refreshable) {
            refreshable.onShow();
        }
        layout.setCenter(screen);
        BorderPane.setMargin(screen, new Insets(14));
        for (NavItem other : navItems) {
            other.button().getStyleClass().remove("selected");
        }
        item.button().getStyleClass().add("selected");
        refreshShiftPill();
        refreshHeldPill();
    }

    /** The number of held sales, so a parked sale is visible from every screen. Hidden when there are none. */
    private void refreshHeldPill() {
        int held = services.holds().list().size();
        heldPill.setText(held + " held");
        heldPill.setVisible(held > 0);
        heldPill.setManaged(held > 0);
    }

    private void refreshShiftPill() {
        Optional<Shift> shift = services.shifts().currentShift();
        shiftPill.getStyleClass().remove("pill-open");
        if (shift.isPresent()) {
            shiftPill.setText("Shift open since " + Ui.dateTime(shift.get().openedAt()));
            shiftPill.getStyleClass().add("pill-open");
        } else {
            shiftPill.setText("No shift open");
        }
    }

    /** Logs out, asking first if a sale is in progress, because logging out discards the cart. */
    private void logOut() {
        Node checkout = screens.get("Checkout");
        boolean saleInProgress = checkout instanceof CheckoutView view && view.hasItems();
        if (saleInProgress && !Ui.confirm("Discard the sale in progress and log out?")) {
            return;
        }
        services.auth().logout();
        onLogout.run();
    }

    private static String roleLabel(Role role) {
        return switch (role) {
            case CASHIER -> "Cashier";
            case MANAGER -> "Manager";
            case ADMIN -> "Owner";
        };
    }

    /** Opens a screen by its sidebar name, as a click would. Used by the screenshot tests. */
    void navigate(String name) {
        navItems.stream().filter(item -> item.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No screen named " + name))
                .button().fire();
    }
}
