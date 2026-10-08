package com.example.app.ui;

import com.example.app.domain.User;
import com.example.app.error.BusinessException;
import com.example.app.service.AppServices;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.input.InputEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.Instant;
import java.util.Optional;

/**
 * Covers the window with a password prompt after a period without input. The cart and the screen are kept; unlocking
 * checks the same user's password, and a wrong password counts towards the normal login lock.
 */
final class IdleLock {

    private final AppServices services;
    private final StackPane host;
    private final java.time.Duration limit;
    private final Timeline ticker;
    private Instant lastActivity = Instant.now();
    private StackPane overlay;

    /** @param limit how long without input before locking; zero or less turns the lock off */
    IdleLock(AppServices services, StackPane host, java.time.Duration limit) {
        this.services = services;
        this.host = host;
        this.limit = limit;
        EventHandler<InputEvent> activity = event -> lastActivity = Instant.now();
        host.addEventFilter(javafx.scene.input.MouseEvent.ANY, activity);
        host.addEventFilter(javafx.scene.input.KeyEvent.ANY, activity);
        this.ticker = new Timeline(new KeyFrame(Duration.seconds(5), e -> checkIdle()));
        this.ticker.setCycleCount(Animation.INDEFINITE);
    }

    void start() {
        if (!limit.isZero() && !limit.isNegative()) {
            ticker.play();
        }
    }

    void stop() {
        ticker.stop();
    }

    private void checkIdle() {
        if (overlay == null && java.time.Duration.between(lastActivity, Instant.now()).compareTo(limit) > 0) {
            lock();
        }
    }

    private void lock() {
        Optional<User> who = services.session().current();
        if (who.isEmpty()) {
            return;
        }
        String username = who.get().username();

        PasswordField password = new PasswordField();
        password.setPromptText("Your password");
        Label message = Ui.error("");
        Button unlock = new Button("Unlock");
        unlock.getStyleClass().add("primary");
        unlock.setDefaultButton(true);
        unlock.setMaxWidth(Double.MAX_VALUE);
        unlock.setOnAction(e -> {
            try {
                services.auth().login(username, password.getText());
                host.getChildren().remove(overlay);
                overlay = null;
                lastActivity = Instant.now();
            } catch (BusinessException ex) {
                message.setText(ex.getMessage());
                password.clear();
                password.requestFocus();
            }
        });

        VBox card = new VBox(10,
                Ui.heading("Locked"),
                Ui.muted("Locked after a period without use. Enter your password to continue; the sale in progress is kept."),
                password, unlock, message);
        card.getStyleClass().add("lock-card");
        card.setMaxWidth(380);
        card.setMaxHeight(VBox.USE_PREF_SIZE);

        overlay = new StackPane(card);
        overlay.getStyleClass().add("lock-overlay");
        StackPane.setAlignment(card, Pos.CENTER);
        host.getChildren().add(overlay);
        password.requestFocus();
    }
}
