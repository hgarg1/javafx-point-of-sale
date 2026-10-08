package com.example.app.ui;

import com.example.app.service.AppServices;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Builds every screen on the JavaFX thread so constructor-time errors fail the build, not a login. */
class ScreensSmokeTest {

    @BeforeAll
    static void startToolkit() {
        try {
            Platform.startup(() -> {
            });
        } catch (IllegalStateException alreadyStarted) {
            // Another test in this JVM started the toolkit.
        }
    }

    @Test
    void everyScreenBuildsForAnAdmin() throws Exception {
        try (AppServices app = new AppServices("jdbc:h2:mem:" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1")) {
            app.auth().completeSetup("owner", "owner-pass-1");
            onFxThread(() -> {
                AppShell shell = new AppShell(app, UiPrefs.defaults(), () -> {
                });
                for (String screen : new String[]{"Checkout", "Shift", "Refunds", "Products", "Stock", "Purchasing", "Reports", "Users", "Settings"}) {
                    shell.navigate(screen);
                }
                assertNotNull(shell);
                assertNotNull(new ShiftView(app));
                assertNotNull(new ProductsView(app));
                assertNotNull(new StockView(app));
                assertNotNull(new RefundView(app));
                assertNotNull(new PurchasingView(app));
                assertNotNull(new ReportsView(app));
                assertNotNull(new UsersView(app));
                return null;
            });
        }
    }

    private static <T> T onFxThread(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(60, TimeUnit.SECONDS);
    }
}
