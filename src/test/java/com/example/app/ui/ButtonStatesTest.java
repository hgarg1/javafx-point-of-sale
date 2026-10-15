package com.example.app.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.css.TransitionEvent;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Paint;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies the CSS cascade, actual transition events, and disabled/reduced-motion behavior. */
class ButtonStatesTest {
    private static final PseudoClass HOVER = PseudoClass.getPseudoClass("hover");
    private static final PseudoClass PRESSED = PseudoClass.getPseudoClass("pressed");
    private record Row(Button resting, Button hovered, Button pressed, Button disabled) { }
    private record Preview(Stage stage, Parent root, List<Row> rows, AtomicInteger events) { }

    @BeforeAll
    static void toolkit() {
        try { Platform.startup(() -> {}); } catch (IllegalStateException alreadyStarted) { }
        Platform.setImplicitExit(false);
    }

    @Test
    void hoverAndPressAnimateWithoutChangingLayoutAndDisabledButtonsStayStill() throws Exception {
        for (ThemeManager.Mode mode : new ThemeManager.Mode[]{ThemeManager.Mode.LIGHT, ThemeManager.Mode.DARK}) {
            Preview preview = fx(() -> preview(mode));
            try {
                settle();
                fx(() -> {
                    preview.rows().forEach(row -> {
                        row.hovered().pseudoClassStateChanged(HOVER, true);
                        row.pressed().pseudoClassStateChanged(HOVER, true);
                        row.pressed().pseudoClassStateChanged(PRESSED, true);
                        row.disabled().pseudoClassStateChanged(HOVER, true);
                    });
                    preview.root().applyCss();
                    return null;
                });
                settle();
                fx(() -> {
                    assertTrue(preview.events().get() > 0, "Hover must start native CSS transitions");
                    for (Row row : preview.rows()) {
                        assertNotEquals(fill(row.resting()), fill(row.hovered()), "Hover needs a distinct fill");
                        assertEquals(row.resting().getLayoutBounds(), row.hovered().getLayoutBounds(), "Hover must not resize controls");
                        assertEquals(0, row.disabled().getTranslateY());
                        assertEquals(1, row.disabled().getScaleX());
                        assertEquals(fill(row.resting()), fill(row.disabled()), "Disabled hover must retain the resting fill");
                    }
                    Row logout = preview.rows().getLast();
                    assertEquals(3, logout.hovered().getGraphic().getTranslateX(), .01);
                    assertEquals(-1, logout.hovered().getTranslateY(), .01);
                    assertEquals(.98, logout.pressed().getScaleX(), .01);
                    if (Boolean.getBoolean("screens")) {
                        PngWriter.write(preview.root().snapshot(null, null), Path.of("target", "screens",
                                "button-states-" + mode.name().toLowerCase() + ".png"));
                    }
                    preview.rows().forEach(row -> {
                        row.hovered().pseudoClassStateChanged(HOVER, false);
                        row.pressed().pseudoClassStateChanged(PRESSED, false);
                        row.pressed().pseudoClassStateChanged(HOVER, false);
                    });
                    preview.root().applyCss();
                    return null;
                });
                settle();
                fx(() -> {
                    assertEquals(0, preview.rows().getLast().hovered().getTranslateY(), .01);
                    assertEquals(0, preview.rows().getLast().hovered().getGraphic().getTranslateX(), .01);
                    preview.root().getStyleClass().add("reduced-motion");
                    preview.rows().getLast().hovered().pseudoClassStateChanged(HOVER, true);
                    preview.root().applyCss();
                    assertEquals(0, preview.rows().getLast().hovered().getTranslateY());
                    assertEquals(0, preview.rows().getLast().hovered().getGraphic().getTranslateX());
                    assertNotEquals(fill(preview.rows().getLast().resting()), fill(preview.rows().getLast().hovered()));
                    return null;
                });
            } finally {
                fx(() -> { preview.stage().close(); ThemeManager.setMode(ThemeManager.Mode.SYSTEM); return null; });
            }
        }
    }

    private static Preview preview(ThemeManager.Mode mode) {
        ThemeManager.setMode(mode);
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(16);
        grid.addRow(0, new Label("Button"), new Label("Rest"), new Label("Hover"), new Label("Pressed"), new Label("Disabled + hover"));
        String[][] variants = {
                {"Secondary", ""}, {"Primary", "primary"}, {"Destructive", "danger-quiet"},
                {"Danger", "danger"}, {"Navigation", "nav-button"}, {"Selected navigation", "nav-button", "selected"},
                {"Logout", "logout-button"}
        };
        List<Row> rows = new ArrayList<>();
        AtomicInteger events = new AtomicInteger();
        for (int i = 0; i < variants.length; i++) {
            String[] variant = variants[i];
            List<Button> buttons = new ArrayList<>();
            for (int column = 0; column < 4; column++) {
                Button button = new Button(variant[0].equals("Logout") ? "Log out" : "Example action");
                for (int style = 1; style < variant.length; style++) {
                    if (!variant[style].isEmpty()) { button.getStyleClass().add(variant[style]); }
                }
                button.setMinWidth(176);
                button.setPrefWidth(176);
                button.setMaxWidth(176);
                button.setPrefHeight(42);
                if (variant[0].equals("Logout")) { button.setGraphic(Icons.icon("logout")); }
                else if (variant[0].contains("navigation") || variant[0].equals("Navigation")) { button.setGraphic(Icons.icon("products")); }
                button.addEventHandler(TransitionEvent.START, e -> events.incrementAndGet());
                buttons.add(button);
            }
            buttons.getLast().setDisable(true);
            Row row = new Row(buttons.get(0), buttons.get(1), buttons.get(2), buttons.get(3));
            rows.add(row);
            grid.addRow(i + 1, new Label(variant[0]), row.resting(), row.hovered(), row.pressed(), row.disabled());
        }
        Label title = Ui.heading("Button interactions");
        VBox root = new VBox(24, title, Ui.muted("160 ms color and lift transitions · 70 ms press feedback · reduced motion supported"), grid);
        root.setPadding(new Insets(28));
        root.setMouseTransparent(true);
        Stage stage = new Stage();
        stage.setScene(ThemeManager.newScene(root, 1030, 575));
        root.getStyleClass().remove("reduced-motion");
        stage.show();
        root.applyCss();
        root.layout();
        return new Preview(stage, root, rows, events);
    }

    private static Paint fill(Button button) { return button.getBackground().getFills().getFirst().getFill(); }
    private static void settle() throws Exception {
        FutureTask<Void> complete = new FutureTask<>(() -> null);
        Platform.runLater(() -> {
            PauseTransition pause = new PauseTransition(Duration.millis(260));
            pause.setOnFinished(e -> complete.run());
            pause.play();
        });
        complete.get(10, TimeUnit.SECONDS);
    }
    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(30, TimeUnit.SECONDS);
    }
}
