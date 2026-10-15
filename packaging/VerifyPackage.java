package com.example.app.ui;

import com.example.app.App;
import com.example.app.domain.ProductDraft;
import com.example.app.domain.TenderCodes;
import com.example.app.service.AppServices;
import com.example.app.service.CheckoutService.CartItem;
import com.example.app.service.CheckoutService.PaymentRequest;
import com.example.app.util.PasswordHasher;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Acceptance helper compiled outside the shipped payload; always run with an isolated test user.home. */
public final class VerifyPackage {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !List.of("ui", "seed", "check").contains(args[0]))
            throw new IllegalArgumentException("Use ui|seed|check app|demo BASE64_TEST_HOME");
        // Avoid the Windows native launcher's legacy encoding for command-line system properties.
        System.setProperty("user.home", new String(java.util.Base64.getDecoder().decode(args[2]), java.nio.charset.StandardCharsets.UTF_8));
        boolean demo = args[1].equals("demo");
        System.setProperty("pos.demo", Boolean.toString(demo));
        Path sandbox = Path.of(System.getProperty("user.home")).toAbsolutePath();
        if (!Files.exists(sandbox.resolve(".packaging-test-home"))) throw new IllegalArgumentException("Missing test-home marker; refusing to use real store data");
        if (args[0].equals("ui")) {
            Platform.startup(() -> {}); Platform.setImplicitExit(false);
            FutureTask<Void> verify = new FutureTask<>(() -> {
                App app = new App(); Stage stage = new Stage();
                try {
                    app.start(stage); stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
                    var root = stage.getScene().getRoot();
                    if (root.lookup("#setup-submit") != null) {
                        ((TextField)root.lookup("#setup-username")).setText("owner");
                        ((TextField)root.lookup("#setup-password")).setText("test-owner-1");
                        ((TextField)root.lookup("#setup-confirm")).setText("test-owner-1");
                        ((Button)root.lookup("#setup-submit")).fire();
                    } else {
                        ((TextField)root.lookup("#login-username")).setText("owner");
                        ((TextField)root.lookup("#login-password")).setText(demo ? "owner-pass-1" : "test-owner-1");
                        ((Button)root.lookup("#login-submit")).fire();
                    }
                    if (!(stage.getScene().getRoot() instanceof AppShell shell)) throw new AssertionError("Packaged sign-in/setup failed");
                    for (String screen : List.of("Shift", "Reports", "Products", "Settings", "Checkout")) {
                        shell.navigate(screen); shell.applyCss(); shell.layout(); shell.snapshot(null, null);
                        if (screen.equals("Reports")) {
                            DatePicker picker = (DatePicker)shell.lookup(".date-picker"); picker.show();
                            boolean calendar = Window.getWindows().stream().anyMatch(window -> window.getScene().getRoot().lookup(".date-picker-popup") != null);
                            if (!calendar) throw new AssertionError("Packaged calendar failed to open"); picker.hide();
                        }
                    }
                    if (demo != stage.getTitle().contains("[DEMO]")) throw new AssertionError("Incorrect window identity");
                } finally { stage.hide(); app.stop(); }
                return null;
            });
            Platform.runLater(verify);
            try { verify.get(45, TimeUnit.SECONDS); } finally { Platform.exit(); }
        } else {
            Path home = sandbox.resolve(demo ? ".cashier-app-demo" : ".cashier-app");
            String jdbc = "jdbc:h2:file:" + home.resolve("data/pos").toString().replace('\\', '/');
            try (AppServices app = new AppServices(jdbc, new PasswordHasher(), home.resolve("backups"))) {
                app.auth().login("owner", demo ? "owner-pass-1" : "test-owner-1");
                if (args[0].equals("seed")) {
                    var product = app.inventory().createProduct(new ProductDraft("INSTALL-QA", null, "Installer test coffee", null, null, 500, 0, 0, 0, true), 3);
                    if (app.shifts().currentShift().isEmpty()) app.shifts().openShift(20000);
                    var sale = app.checkout().checkout(List.of(new CartItem(product, 1, 0)), List.of(new PaymentRequest(TenderCodes.CASH, 500, null)), null, "installer-qa");
                    Files.writeString(home.resolve("qa-receipt.txt"), sale.sale().receiptNo());
                    UiPrefs prefs = UiPrefs.load(home); prefs.setThemeMode(ThemeManager.Mode.DARK); prefs.save();
                    app.backups().backupNow("installer-test");
                }
                String receipt = Files.readString(home.resolve("qa-receipt.txt"));
                if (app.refunds().findSaleByReceipt(receipt).isEmpty()) throw new AssertionError("Saved sale was lost");
                if (UiPrefs.load(home).themeMode() != ThemeManager.Mode.DARK) throw new AssertionError("Preferences were lost");
                app.reports().exportLedgerCsv(LocalDate.now(), LocalDate.now(), home.resolve("qa-ledger.csv"));
                if (!Files.exists(home.resolve("qa-ledger.csv"))) throw new AssertionError("Export failed");
                if (app.backups().listBackups().isEmpty()) throw new AssertionError("Backup missing");
            }
        }
        System.out.println("Packaged acceptance passed: " + args[0] + " " + args[1]);
    }
}
