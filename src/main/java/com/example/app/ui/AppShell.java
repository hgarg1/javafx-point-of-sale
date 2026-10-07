package com.example.app.ui;

import com.example.app.domain.Role;
import com.example.app.domain.User;
import com.example.app.service.AppServices;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.function.Supplier;

/** Main window after login: a toolbar with the screens this role may use, and the active screen. */
public final class AppShell extends BorderPane {

    private final AppServices services;
    private final User user;
    private final Runnable onLogout;
    private final HBox nav = new HBox(6);

    public AppShell(AppServices services, Runnable onLogout) {
        this.services = services;
        this.user = services.session().requireUser();
        this.onLogout = onLogout;

        Label who = Ui.muted(user.username() + " (" + user.role() + ")");
        Button logout = new Button("Log out");
        logout.setOnAction(e -> {
            services.auth().logout();
            onLogout.run();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(10, nav, spacer, who, logout);
        toolbar.setPadding(new Insets(8));
        toolbar.setStyle("-fx-background-color: #f4f4f4; -fx-border-color: #d0d0d0; -fx-border-width: 0 0 1 0;");

        addNav("Checkout", () -> new CheckoutView(services), Role.CASHIER);
        addNav("Shift", () -> new ShiftView(services), Role.CASHIER);
        addNav("Products", () -> new ProductsView(services), Role.MANAGER);
        addNav("Stock", () -> new StockView(services), Role.MANAGER);
        addNav("Refunds", () -> new RefundView(services), Role.MANAGER);
        addNav("Reports", () -> new ReportsView(services), Role.MANAGER);
        addNav("Users", () -> new UsersView(services), Role.ADMIN);

        setTop(toolbar);
        setPadding(new Insets(12));
        // Start on the first screen the role can use.
        ((Button) nav.getChildren().get(0)).fire();
    }

    private void addNav(String label, Supplier<Node> screen, Role minimum) {
        if (!user.role().atLeast(minimum)) {
            return;
        }
        Button button = new Button(label);
        button.setOnAction(e -> setCenter(screen.get()));
        nav.getChildren().add(button);
    }
}
