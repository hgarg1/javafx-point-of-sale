package com.example.app.ui;

import com.example.app.error.BusinessException;
import com.example.app.service.AppServices;
import com.example.app.util.PasswordHasher;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.BorderPane;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceControlsTest {
    private record Count(String name, StringProperty quantity) { }

    @BeforeAll
    static void toolkit() {
        try { Platform.startup(() -> {}); } catch (IllegalStateException started) { }
        Platform.setImplicitExit(false);
    }

    private static AppServices app() {
        AppServices app = new AppServices("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", new PasswordHasher(1000));
        DemoData.seed(app);
        app.auth().login("owner", "owner-pass-1");
        return app;
    }

    @Test
    void refundInputsExpandAndTypedQuantityIsUsedImmediately() throws Exception {
        try (AppServices app = app()) {
            fx(() -> {
                RefundView view = new RefundView(app);
                Stage stage = show(view, 1024, 680);
                try {
                    TextField receipt = field(view, "receiptNo");
                    receipt.setText("R001001");
                    Event.fireEvent(receipt, new ActionEvent());
                    view.applyCss(); view.layout();
                    TextField reason = field(view, "reason");
                    assertTrue(reason.getWidth() > 650, "Refund reason must use the available row width");
                    TextField quantity = (TextField) view.lookupAll(".table-input").stream()
                            .filter(Node::isVisible).findFirst().orElseThrow();
                    assertTrue(quantity.getWidth() >= 110, "Refund quantity must remain editable and readable");
                    quantity.setText("1");
                    reason.setText("Customer returned one item");
                    if (Boolean.getBoolean("screens")) {
                        for (ThemeManager.Mode mode : new ThemeManager.Mode[]{ThemeManager.Mode.LIGHT, ThemeManager.Mode.DARK}) {
                            ThemeManager.setMode(mode); view.applyCss(); view.layout();
                            PngWriter.write(view.snapshot(null, null), Path.of("target", "screens",
                                    "refunds-loaded-" + mode.name().toLowerCase() + ".png"));
                        }
                    }
                    Platform.runLater(() -> {
                        for (Window window : List.copyOf(Window.getWindows())) {
                            Node root = window.getScene().getRoot().lookup(".dialog-pane");
                            if (root instanceof DialogPane pane && pane.getButtonTypes().contains(ButtonType.YES)) {
                                ((Button) pane.lookupButton(ButtonType.YES)).fire(); return;
                            }
                        }
                        throw new AssertionError("Expected refund confirmation");
                    });
                    view.lookupAll(".button").stream().filter(Button.class::isInstance).map(Button.class::cast)
                            .filter(button -> "Record refund".equals(button.getText())).findFirst().orElseThrow().fire();
                    var sale = app.refunds().findSaleByReceipt("R001001").orElseThrow();
                    assertEquals(1, app.refunds().refundableLines(sale.id()).getFirst().refundedQty());
                    assertEquals("", reason.getText());
                    return null;
                } finally { stage.close(); ThemeManager.setMode(ThemeManager.Mode.SYSTEM); }
            });
        }
    }

    @Test
    void tableEditorsFollowRowsAfterSortingAndExternalUpdates() throws Exception {
        fx(() -> {
            Count a = new Count("A", new SimpleStringProperty("1"));
            Count b = new Count("B", new SimpleStringProperty("2"));
            TableView<Count> table = Ui.table();
            var name = Columns.text("Item", 300, Count::name);
            var input = Columns.input("Count", 180, Count::quantity);
            table.getColumns().addAll(name, input);
            table.getItems().addAll(a, b);
            Stage stage = show(new BorderPane(table), 700, 350);
            try {
                TextField first = editor(table, 0);
                first.setText("3");
                assertEquals("3", a.quantity().get());
                name.setSortType(TableColumn.SortType.DESCENDING);
                table.getSortOrder().add(name); table.sort();
                table.applyCss(); table.layout();
                first = editor(table, 0);
                first.setText("7");
                assertEquals("7", b.quantity().get());
                assertEquals("3", a.quantity().get());
                b.quantity().set("22");
                assertEquals("22", first.getText());
                return null;
            } finally { stage.close(); }
        });
    }

    @Test
    void calendarPopupMatchesDarkThemeAndBothTypingAndClickingSetDates() throws Exception {
        try (AppServices app = app()) {
            fx(() -> {
                ThemeManager.setMode(ThemeManager.Mode.DARK);
                ReportsView view = new ReportsView(app);
                Stage stage = show(view, 1150, 740);
                try {
                    DatePicker picker = field(view, "from");
                    picker.getEditor().setText("10/1/2026");
                    assertEquals(LocalDate.of(2026, 10, 1), ReportsView.requireDate(picker, "From"));
                    picker.getEditor().setText("not a date");
                    assertThrows(BusinessException.class, () -> ReportsView.requireDate(picker, "From"));
                    picker.setValue(LocalDate.now().withDayOfMonth(1));
                    picker.show();
                    Parent popup = Window.getWindows().stream().filter(PopupWindow.class::isInstance)
                            .filter(Window::isShowing).map(window -> window.getScene().getRoot())
                            .filter(root -> root.lookup(".date-picker-popup") != null).findFirst().orElseThrow();
                    popup.applyCss(); popup.layout();
                    assertTrue(popup.getStyleClass().contains("dark"));
                    Node calendar = popup.lookup(".date-picker-popup");
                    if (Boolean.getBoolean("screens")) {
                        PngWriter.write(calendar.snapshot(null, null), Path.of("target", "screens", "calendar-dark.png"));
                    }
                    LocalDate selected = LocalDate.now().withDayOfMonth(17);
                    DateCell cell = calendar.lookupAll(".day-cell").stream().filter(DateCell.class::isInstance)
                            .map(DateCell.class::cast).filter(day -> selected.equals(day.getItem())).findFirst().orElseThrow();
                    Event.fireEvent(cell, new MouseEvent(MouseEvent.MOUSE_CLICKED, 4, 4, 4, 4, MouseButton.PRIMARY,
                            1, false, false, false, false, false, false, false, false, false, true, new PickResult(cell, 4, 4)));
                    assertEquals(selected, picker.getValue());
                    picker.hide();
                    return null;
                } finally { stage.close(); ThemeManager.setMode(ThemeManager.Mode.SYSTEM); }
            });
        }
    }

    @Test
    void workspacePanelsHaveWorkingDividersAndEveryScreenFitsTheMinimumWindow() throws Exception {
        try (AppServices app = app()) {
            fx(() -> {
                for (ThemeManager.Mode mode : new ThemeManager.Mode[]{ThemeManager.Mode.LIGHT, ThemeManager.Mode.DARK}) {
                    ThemeManager.setMode(mode);
                    AppShell shell = new AppShell(app, UiPrefs.defaults(), () -> {});
                    Stage stage = show(shell, 1024, 680);
                    try {
                        for (String screen : new String[]{"Checkout", "Products", "Settings", "Stock", "Purchasing", "Refunds", "Reports", "Users", "Shift"}) {
                            shell.navigate(screen); shell.applyCss(); shell.layout();
                            var splits = shell.lookupAll(".workspace-split").stream().map(SplitPane.class::cast)
                                    .sorted(java.util.Comparator.comparingInt(WorkspaceControlsTest::depth)).toList();
                            var originalPositions = new java.util.LinkedHashMap<SplitPane, double[]>();
                            splits.forEach(split -> originalPositions.put(split, split.getDividerPositions()));
                            for (SplitPane split : splits) {
                                assertTrue(split.getItems().size() >= 2);
                                for (Node item : split.getItems()) {
                                    if (item instanceof javafx.scene.layout.Region region) {
                                        assertTrue(region.getWidth() + 1 >= region.getMinWidth());
                                    }
                                }
                                double[] original = split.getDividerPositions();
                                split.setDividerPositions(.05); shell.layout();
                                double start = split.getDividerPositions()[0];
                                split.setDividerPositions(.90); shell.layout();
                                assertTrue(split.getDividerPositions()[0] > start, screen + " divider cannot resize");
                                split.setDividerPositions(original); shell.layout();
                            }
                            originalPositions.forEach(SplitPane::setDividerPositions);
                            shell.layout(); shell.layout();
                            if (Boolean.getBoolean("screens")) {
                                shell.getStyleClass().add("theme-changing");
                                shell.applyCss(); shell.layout();
                                PngWriter.write(shell.snapshot(null, null), Path.of("target", "screens",
                                        "workspace-" + screen.toLowerCase() + "-" + mode.name().toLowerCase() + "-1024.png"));
                                shell.getStyleClass().remove("theme-changing");
                            }
                        }
                    } finally { stage.close(); }
                }
                ThemeManager.setMode(ThemeManager.Mode.SYSTEM);
                return null;
            });
        }
    }

    private static TextField editor(TableView<?> table, int index) {
        return table.lookupAll(".table-input").stream().map(TextField.class::cast).filter(field -> {
            Node node = field.getParent();
            while (node != null && !(node instanceof TableCell)) { node = node.getParent(); }
            return node instanceof TableCell<?, ?> cell && cell.getIndex() == index;
        }).findFirst().orElseThrow();
    }
    private static int depth(Node node) {
        int count = 0; while (node.getParent() != null) { count++; node = node.getParent(); } return count;
    }
    private static Stage show(Parent root, double width, double height) {
        Stage stage = new Stage(); stage.setScene(ThemeManager.newScene(root, width, height)); stage.show();
        root.applyCss(); root.layout(); return stage;
    }
    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return (T) field.get(target);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static <T> T fx(java.util.concurrent.Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); Platform.runLater(task); return task.get(60, TimeUnit.SECONDS);
    }
}
