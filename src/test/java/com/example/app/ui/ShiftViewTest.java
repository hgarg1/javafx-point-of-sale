package com.example.app.ui;

import com.example.app.service.AppServices;
import com.example.app.util.PasswordHasher;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ShiftViewTest {
    @BeforeAll
    static void toolkit() {
        try { Platform.startup(() -> {}); } catch (IllegalStateException started) { }
        Platform.setImplicitExit(false);
    }

    @Test
    void openingAmountPresetsSubmitTheSelectedFloat() throws Exception {
        try (AppServices app = app()) {
            app.shifts().closeShift(20000);
            fx(() -> {
                ShiftView view = new ShiftView(app);
                Stage stage = show(view);
                try {
                    if (Boolean.getBoolean("screens")) {
                        for (ThemeManager.Mode mode : new ThemeManager.Mode[]{ThemeManager.Mode.LIGHT, ThemeManager.Mode.DARK}) {
                            ThemeManager.setMode(mode); view.applyCss(); view.layout();
                            view.getStyleClass().add("theme-changing"); view.applyCss(); view.layout();
                            PngWriter.write(view.snapshot(null, null), Path.of("target", "screens", "shift-ready-" + mode.name().toLowerCase() + ".png"));
                            view.getStyleClass().remove("theme-changing");
                        }
                    }
                    button(view, "$300.00").fire();
                    assertEquals("300.00", ((TextField) view.lookup("#shift-opening-float")).getText());
                    button(view, "Open shift").fire();
                    assertEquals(30000, app.shifts().currentShift().orElseThrow().openingFloatCents());
                    view.applyCss(); view.layout();
                    assertNotNull(view.lookup("#shift-counted-cash"));
                    return null;
                } finally { stage.close(); ThemeManager.setMode(ThemeManager.Mode.SYSTEM); }
            });
        }
    }

    @Test
    void cancellingDrawerConfirmationKeepsTheShiftAndCount() throws Exception {
        try (AppServices app = app()) {
            long shift = app.shifts().currentShift().orElseThrow().id();
            fx(() -> {
                ShiftView view = new ShiftView(app);
                Stage stage = show(view);
                try {
                    TextField counted = (TextField) view.lookup("#shift-counted-cash");
                    counted.setText("220.50");
                    Platform.runLater(() -> {
                        for (Window window : List.copyOf(Window.getWindows())) {
                            Node node = window.getScene().getRoot().lookup(".dialog-pane");
                            if (node instanceof DialogPane pane && pane.getButtonTypes().contains(ButtonType.NO)) {
                                ((Button) pane.lookupButton(ButtonType.NO)).fire(); return;
                            }
                        }
                        throw new AssertionError("Expected closing confirmation");
                    });
                    ((Button) view.lookup("#shift-close")).fire();
                    assertEquals(shift, app.shifts().currentShift().orElseThrow().id());
                    assertEquals("220.50", counted.getText());
                    assertFalse(view.lookupAll(".label").stream().map(Label.class::cast)
                            .anyMatch(label -> label.getText().startsWith("Expected cash")));
                    return null;
                } finally { stage.close(); }
            });
        }
    }

    private static AppServices app() {
        AppServices app = new AppServices("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", new PasswordHasher(1000));
        DemoData.seed(app); app.auth().login("owner", "owner-pass-1"); return app;
    }
    private static Button button(ShiftView view, String title) {
        return view.lookupAll(".button").stream().map(Button.class::cast).filter(button -> title.equals(button.getText())).findFirst().orElseThrow();
    }
    private static Stage show(ShiftView view) {
        Stage stage = new Stage(); stage.setScene(ThemeManager.newScene(view, 1024, 680)); stage.show();
        view.applyCss(); view.layout(); return stage;
    }
    private static <T> T fx(java.util.concurrent.Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); Platform.runLater(task); return task.get(30, TimeUnit.SECONDS);
    }
}
