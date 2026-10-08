package com.example.app.ui;

import com.example.app.service.AppServices;
import com.example.app.util.PasswordHasher;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The change-password dialog is what a forced first login shows. It has to open with its whole content inside the window,
 * including the Save and Cancel buttons, or the person cannot go on.
 */
class ChangePasswordDialogTest {

    @BeforeAll
    static void startToolkit() {
        try {
            Platform.startup(() -> {
            });
        } catch (IllegalStateException alreadyStarted) {
            // Shared toolkit from another test.
        }
        // Closing the last window would otherwise shut the toolkit down for the tests that run after this one.
        Platform.setImplicitExit(false);
    }

    @Test
    void theForcedDialogOpensWithItsButtonsAndHeaderInsideTheWindow() throws Exception {
        try (AppServices app = new AppServices("jdbc:h2:mem:" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1", new PasswordHasher(1_000))) {
            onFxThread(() -> {
                Dialog<ButtonType> dialog = ChangePasswordDialog.build(app, true);
                dialog.show();
                try {
                    DialogPane pane = dialog.getDialogPane();
                    pane.applyCss();
                    pane.layout();
                    double windowWidth = pane.getScene().getWidth();
                    double windowHeight = pane.getScene().getHeight();

                    Button save = (Button) pane.lookupButton(pane.getButtonTypes().get(0));
                    assertInside(save, windowWidth, windowHeight, "Save");
                    Node cancel = pane.lookupButton(ButtonType.CANCEL);
                    assertInside(cancel, windowWidth, windowHeight, "Cancel");

                    Label header = (Label) pane.lookup(".header-panel > .label");
                    assertTrue(header.getWidth() + 1 >= header.prefWidth(-1) || header.isWrapText(),
                            "The header text is cut off on the right");
                    assertTrue(header.getHeight() + 1 >= header.prefHeight(header.getWidth()),
                            "The header text is cut off at the bottom");
                } finally {
                    dialog.close();
                }
                return null;
            });
        }
    }

    private static void assertInside(Node node, double windowWidth, double windowHeight, String name) {
        Bounds bounds = node.localToScene(node.getBoundsInLocal());
        assertTrue(bounds.getMaxY() <= windowHeight,
                name + " ends at y=" + bounds.getMaxY() + " but the window is " + windowHeight + " tall");
        assertTrue(bounds.getMaxX() <= windowWidth,
                name + " ends at x=" + bounds.getMaxX() + " but the window is " + windowWidth + " wide");
    }

    private static <T> T onFxThread(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(60, TimeUnit.SECONDS);
    }
}
