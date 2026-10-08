package com.example.app.ui;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/**
 * A short confirmation that appears over the bottom of the window and goes away on its own. Used for "Saved" and
 * similar messages, so the cashier is not stopped by a dialog they must dismiss. It has no animation, so it is
 * equally suitable when reduced motion is requested.
 */
final class Toast {

    private static final Duration SHOWN_FOR = Duration.seconds(2.5);
    private static StackPane host;

    private Toast() {
    }

    /** The window's overlay layer. The shell calls this once when the window is built. */
    static void attach(StackPane overlayHost) {
        host = overlayHost;
    }

    static void show(String message) {
        if (host == null) {
            return;
        }
        host.getChildren().removeIf(node -> node.getStyleClass().contains("toast"));
        Label toast = new Label(message);
        toast.getStyleClass().add("toast");
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new Insets(0, 0, 28, 0));
        host.getChildren().add(toast);

        PauseTransition wait = new PauseTransition(SHOWN_FOR);
        wait.setOnFinished(e -> host.getChildren().remove(toast));
        wait.play();
    }
}
