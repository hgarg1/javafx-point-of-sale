package com.example.app.ui;

import com.example.app.service.AppServices;
import com.example.app.util.PasswordHasher;
import javafx.application.Platform;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Saves the change-password dialog (forced and normal) and the payment dialog, in light and dark, to {@code target/screens}.
 * It is kept apart from {@link ScreenshotTest}: showing dialogs inside the screen loop slows the screens that follow. Run it
 * with {@code mvn test -Dtest=DialogScreenshotTest -Dscreens=true}.
 */
@EnabledIfSystemProperty(named = "screens", matches = "true")
class DialogScreenshotTest {

    private static final Path OUT = Path.of("target", "screens");

    @BeforeAll
    static void startToolkit() {
        try {
            Platform.startup(() -> {
            });
        } catch (IllegalStateException alreadyStarted) {
            // Shared toolkit from another test.
        }
        // Closing the last window would otherwise shut the toolkit down and stop the next test's work. The app itself keeps
        // its main window open, so it still exits normally.
        Platform.setImplicitExit(false);
    }

    @Test
    void renderChangePasswordDialogInLightAndDark() throws Exception {
        try (AppServices app = new AppServices("jdbc:h2:mem:" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1", new PasswordHasher(1_000))) {
            onFxThread(() -> {
                for (ThemeManager.Mode mode : new ThemeManager.Mode[]{ThemeManager.Mode.LIGHT, ThemeManager.Mode.DARK}) {
                    ThemeManager.setMode(mode);
                    String suffix = mode == ThemeManager.Mode.DARK ? "dark" : "light";
                    renderDialog(ChangePasswordDialog.build(app, true), "change-password-forced-" + suffix);
                    renderDialog(ChangePasswordDialog.build(app, false), "change-password-" + suffix);
                }
                return null;
            });
        }
    }

    @Test
    void renderPaymentDialogInLightAndDark() throws Exception {
        try (AppServices app = new AppServices("jdbc:h2:mem:" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1", new PasswordHasher(1_000))) {
            DemoData.seed(app);
            app.auth().login("owner", "owner-pass-1");

            onFxThread(() -> {
                for (ThemeManager.Mode mode : new ThemeManager.Mode[]{ThemeManager.Mode.LIGHT, ThemeManager.Mode.DARK}) {
                    ThemeManager.setMode(mode);
                    String suffix = mode == ThemeManager.Mode.DARK ? "dark" : "light";
                    renderDialog(PaymentDialog.build(app, 2_450), "payment-" + suffix);
                }
                return null;
            });
        }
    }

    @Test
    void renderReceiptDialogInLightAndDark() throws Exception {
        // Receipts are 40 columns wide, as ReceiptFormatter lays them out.
        String receipt = String.join("\n",
                "My Store",
                "Receipt R000123",
                "",
                "Espresso Beans 250g  2 x 14.50  29.00",
                String.format("%-35s%5s", "Subtotal", "29.00"),
                String.format("%-35s%5s", "Total", "29.00"),
                String.format("%-35s%5s", "Paid by cash", "30.00"),
                String.format("%-35s%5s", "Change", "1.00"),
                "",
                "Thank you for shopping with us");
        onFxThread(() -> {
            for (ThemeManager.Mode mode : new ThemeManager.Mode[]{ThemeManager.Mode.LIGHT, ThemeManager.Mode.DARK}) {
                ThemeManager.setMode(mode);
                String suffix = mode == ThemeManager.Mode.DARK ? "dark" : "light";
                renderDialog(ReceiptDialog.build(receipt), "receipt-" + suffix);
            }
            return null;
        });
    }

    /** Shows the dialog as the person would see it, saves its pane, and closes it again. */
    private static void renderDialog(Dialog<ButtonType> dialog, String name) throws IOException {
        dialog.show();
        try {
            DialogPane pane = dialog.getDialogPane();
            pane.applyCss();
            pane.layout();
            PngWriter.write(pane.snapshot(null, null), OUT.resolve(name + ".png"));
        } finally {
            dialog.close();
        }
    }

    private static <T> T onFxThread(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(120, TimeUnit.SECONDS);
    }
}
