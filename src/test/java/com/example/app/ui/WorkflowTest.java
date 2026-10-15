package com.example.app.ui;

import com.example.app.domain.Product;
import com.example.app.domain.ProductDraft;
import com.example.app.service.AppServices;
import com.example.app.service.CheckoutService.CartItem;
import com.example.app.util.PasswordHasher;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowTest {
    @BeforeAll
    static void toolkit() {
        try { Platform.startup(() -> {}); } catch (IllegalStateException alreadyStarted) { }
        Platform.setImplicitExit(false);
    }

    private static AppServices app() {
        AppServices app = new AppServices("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                new PasswordHasher(1000));
        app.auth().completeSetup("owner", "owner-pass-1");
        app.inventory().createProduct(new ProductDraft("ONE", null, "Coffee one", null, null, 500, 0, 0, 0, true), 3);
        app.inventory().createProduct(new ProductDraft("TWO", null, "Coffee two", null, null, 500, 0, 0, 0, true), 0);
        app.shifts().openShift(0);
        return app;
    }

    @Test
    void declinedPaymentKeepsFieldsAndCorrectedRetryCreatesOneSale() throws Exception {
        try (AppServices app = app()) {
            fx(() -> {
                long id = app.inventory().findByCode("ONE").orElseThrow().id();
                String key = UUID.randomUUID().toString();
                var holder = new java.util.concurrent.atomic.AtomicReference<Dialog<ButtonType>>();
                var submissions = new java.util.concurrent.atomic.AtomicInteger();
                Dialog<ButtonType> dialog = PaymentDialog.build(app, 500, payments -> {
                    submissions.incrementAndGet();
                    // Simulate a repeated submit while the first submission is in progress.
                    ((Button) holder.get().getDialogPane().lookupButton(
                            holder.get().getDialogPane().getButtonTypes().getFirst())).fire();
                    return app.checkout().checkout(List.of(new CartItem(id, 1, 0)), payments, null, key);
                });
                holder.set(dialog);
                dialog.show();
                try {
                    GridPane grid = nodes((Parent) dialog.getDialogPane().getContent(), GridPane.class).getFirst();
                    TextField card = nodes(grid, TextField.class).stream()
                            .filter(field -> "Card number".equals(field.getPromptText())).findFirst().orElseThrow();
                    Integer row = GridPane.getRowIndex(card);
                    TextField amount = nodes(grid, TextField.class).stream()
                            .filter(field -> row.equals(GridPane.getRowIndex(field))
                                    && Integer.valueOf(1).equals(GridPane.getColumnIndex(field)))
                            .findFirst().orElseThrow();
                    amount.setText("5.00");
                    card.setText("4111111111110002");
                    Button submit = (Button) dialog.getDialogPane().lookupButton(dialog.getDialogPane().getButtonTypes().getFirst());
                    submit.fire();
                    assertTrue(dialog.isShowing());
                    assertEquals("5.00", amount.getText());
                    assertEquals("4111111111110002", card.getText());
                    assertEquals(0, app.reports().salesBetween(LocalDate.now(), LocalDate.now()).size());
                    card.setText("4111111111111111");
                    submit.fire();
                    assertFalse(dialog.isShowing());
                    assertEquals(1, app.reports().salesBetween(LocalDate.now(), LocalDate.now()).size());
                    assertEquals(2, app.inventory().findProduct(id).orElseThrow().stockQty());
                    assertEquals(2, submissions.get());
                } finally { dialog.close(); }
                return null;
            });
        }
    }

    @Test
    void cancelledApprovalKeepsPaymentOpenAndGiftCodesRemainAccessibleUntilLogout() throws Exception {
        try (AppServices app = app()) {
            fx(() -> {
                respondFirst();
                var cancelled = PaymentDialog.ask(app, 500, payments -> {
                    Platform.runLater(() -> {
                        DialogPane pane = Window.getWindows().stream().filter(Window::isShowing)
                                .map(window -> window.getScene().getRoot().lookup(".dialog-pane"))
                                .filter(DialogPane.class::isInstance).map(DialogPane.class::cast)
                                .findFirst().orElseThrow();
                        assertTrue(nodes(pane, Label.class).stream()
                                .anyMatch(label -> label.getText().contains("Approval cancelled")));
                        ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();
                    });
                    return null;
                });
                assertTrue(cancelled.isEmpty());
                assertEquals(0, app.reports().salesBetween(LocalDate.now(), LocalDate.now()).size());
                app.inventory().createProduct(new ProductDraft("GIFT", null, "Gift", null, null, 500, 0, 0,
                        0, true, com.example.app.domain.ProductKind.GIFT_CARD), 0);
                AppShell shell = new AppShell(app, UiPrefs.defaults(), () -> {});
                ThemeManager.newScene(shell, 1024, 680);
                var layout = (javafx.scene.layout.BorderPane) field(shell, "workspace");
                CheckoutView view = (CheckoutView) layout.getCenter();
                TextField scan = field(view, "scan");
                scan.setText("GIFT");
                Event.fireEvent(scan, new ActionEvent());
                respondFirst();
                ((Button) field(view, "pay")).fire();
                assertFalse(view.hasItems());
                assertTrue(((Label) field(view, "receiptReminder")).getText().contains("Gift cards issued"));
                Button receipt = field(view, "lastReceipt");
                assertFalse(receipt.isDisabled());
                respond(ButtonType.CLOSE);
                receipt.fire();
                nodes(shell, Button.class).stream().filter(button -> "Log out".equals(button.getText()))
                        .findFirst().orElseThrow().fire();
                assertTrue(app.session().current().isEmpty());
                assertNull(field(view, "lastReceiptText"));
                assertTrue(receipt.isDisabled());
                return null;
            });
        }
    }

    @Test
    void searchAvailabilityCancellationAndOptionalReceiptWorkTogether() throws Exception {
        try (AppServices app = app()) {
            fx(() -> {
                CheckoutView view = new CheckoutView(app, () -> {});
                ThemeManager.newScene(view, 1024, 680);
                view.resize(1024, 680);
                view.applyCss();
                view.layout();
                TextField scan = field(view, "scan");
                TableView<Product> results = field(view, "results");
                scan.setText("Coffee");
                Event.fireEvent(scan, new ActionEvent());
                assertEquals("Coffee", scan.getText());
                assertEquals(2, results.getItems().size());
                Event.fireEvent(scan, key(KeyCode.DOWN));
                Event.fireEvent(results, key(KeyCode.ENTER));
                assertTrue(view.hasItems());
                assertEquals("", scan.getText());
                scan.setText("missing");
                Event.fireEvent(scan, new ActionEvent());
                assertEquals("missing", scan.getText());
                Event.fireEvent(scan, key(KeyCode.ESCAPE));
                assertEquals(2, results.getItems().size());
                Button pay = field(view, "pay");
                respond(ButtonType.CANCEL);
                pay.fire();
                assertTrue(view.hasItems());
                assertEquals(0, app.reports().salesBetween(LocalDate.now(), LocalDate.now()).size());
                respondFirst();
                pay.fire();
                assertFalse(view.hasItems());
                assertFalse(((Button) field(view, "lastReceipt")).isDisabled());
                assertEquals(1, app.reports().salesBetween(LocalDate.now(), LocalDate.now()).size());
                scan.setText("TWO");
                Event.fireEvent(scan, new ActionEvent());
                assertTrue(view.hasItems());
                assertTrue(pay.isDisabled());
                assertTrue(((Label) field(view, "availability")).getText().contains("in stock"));
                assertEquals(500, ((com.example.app.service.CheckoutService.Quote) field(view, "quote")).totalCents());
                return null;
            });
        }
    }

    @Test
    void productDraftSurvivesCancelledSelectionFilterNavigationAndFailedSave() throws Exception {
        try (AppServices app = app()) {
            fx(() -> {
                AppShell shell = new AppShell(app, UiPrefs.defaults(), () -> {});
                ThemeManager.newScene(shell, 1024, 680);
                shell.navigate("Products");
                shell.resize(1024, 680);
                shell.applyCss();
                shell.layout();
                var layout = (javafx.scene.layout.BorderPane) field(shell, "workspace");
                ProductsView view = (ProductsView) layout.getCenter();
                TableView<Product> table = field(view, "table");
                table.getSelectionModel().selectFirst();
                TextField name = field(view, "name");
                name.setText("Changed name");
                respond(ButtonType.CANCEL);
                table.getSelectionModel().select(1);
                settleSelection();
                assertEquals("ONE", table.getSelectionModel().getSelectedItem().sku());
                assertEquals("Changed name", name.getText());
                respond(ButtonType.CANCEL);
                ((TextField) field(view, "filter")).setText("TWO");
                assertEquals("", ((TextField) field(view, "filter")).getText());
                respond(ButtonType.CANCEL);
                shell.navigate("Checkout");
                assertSame(view, layout.getCenter());
                respond(ButtonType.CANCEL);
                assertFalse(shell.canLeave());
                respond(ButtonType.CANCEL);
                nodes(view, Button.class).stream().filter(button -> "New product".equals(button.getText()))
                        .findFirst().orElseThrow().fire();
                assertEquals("Changed name", name.getText());
                respond(ButtonType.CANCEL);
                nodes(shell, Button.class).stream().filter(button -> "Log out".equals(button.getText()))
                        .findFirst().orElseThrow().fire();
                assertTrue(app.session().current().isPresent());
                name.clear();
                respondText("Save");
                assertFalse(view.canLeave());
                assertEquals("", name.getText());
                assertTrue(view.hasDraft());
                name.setText("Saved name");
                respondText("Save");
                shell.navigate("Checkout");
                assertInstanceOf(CheckoutView.class, layout.getCenter());
                assertEquals("Saved name", app.inventory().findByCode("ONE").orElseThrow().name());
                shell.navigate("Products");
                table.getSelectionModel().select(table.getItems().stream()
                        .filter(product -> "ONE".equals(product.sku())).findFirst().orElseThrow());
                name.setText("Discarded name");
                respondText("Discard");
                assertTrue(view.canLeave());
                assertFalse(view.hasDraft());
                assertEquals("Saved name", name.getText());
                return null;
            });
        }
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }

    private static void settleSelection() {
        Object loop = new Object();
        Platform.runLater(() -> Platform.exitNestedEventLoop(loop, null));
        Platform.enterNestedEventLoop(loop);
    }

    private static void respond(ButtonType type) { respondPane(pane -> pane.lookupButton(type)); }
    private static void respondFirst() { respondPane(pane -> pane.lookupButton(pane.getButtonTypes().getFirst())); }
    private static void respondText(String text) {
        respondPane(pane -> pane.lookupButton(pane.getButtonTypes().stream()
                .filter(type -> text.equals(type.getText())).findFirst().orElseThrow()));
    }
    private static void respondPane(java.util.function.Function<DialogPane, Node> choice) {
        Platform.runLater(() -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window.isShowing() && window.getScene() != null) {
                    Node pane = window.getScene().getRoot().lookup(".dialog-pane");
                    if (pane instanceof DialogPane dialog) {
                        ((Button) choice.apply(dialog)).fire();
                        return;
                    }
                }
            }
            throw new AssertionError("Expected a dialog");
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(target);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static <T extends Node> List<T> nodes(Parent parent, Class<T> type) {
        var found = new java.util.ArrayList<T>();
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (type.isInstance(child)) { found.add(type.cast(child)); }
            if (child instanceof Parent nested) { found.addAll(nodes(nested, type)); }
        }
        return found;
    }
    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(60, TimeUnit.SECONDS);
    }
}
