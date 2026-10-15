package com.example.app.ui;

import com.example.app.domain.User;
import com.example.app.service.AppServices;
import com.example.app.util.PasswordHasher;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AuthViewTest {
    @BeforeAll
    static void toolkit() {
        try { Platform.startup(() -> {}); } catch (IllegalStateException alreadyStarted) { }
        Platform.setImplicitExit(false);
    }

    private static AppServices app() {
        return new AppServices("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", new PasswordHasher(1000));
    }

    @Test
    void signInSupportsInlineErrorsRevealAndKeyboardSubmission() throws Exception {
        try (AppServices app = app()) {
            app.auth().completeSetup("owner", "owner-pass-1");
            app.auth().logout();
            fx(() -> {
                AtomicReference<User> loggedIn = new AtomicReference<>();
                LoginView view = new LoginView(app, loggedIn::set);
                layout(view, 1024, 680);
                TextField username = (TextField) view.lookup("#login-username");
                PasswordField password = (PasswordField) view.lookup("#login-password");
                Button submit = (Button) view.lookup("#login-submit");
                submit.fire();
                Label error = (Label) view.lookup("#login-error");
                assertTrue(error.isVisible());
                assertTrue(error.getText().contains("username and password"));
                username.setText("owner");
                password.setText("wrong-password");
                submit.fire();
                assertNull(loggedIn.get());
                assertEquals("owner", username.getText());
                assertFalse(error.getText().isEmpty());
                ((Button) view.lookup("#login-reveal")).fire();
                assertFalse(password.isVisible());
                TextField revealed = (TextField) view.lookupAll(".auth-password-input").stream()
                        .filter(Node::isVisible).findFirst().orElseThrow();
                assertEquals("wrong-password", revealed.getText());
                revealed.setText("owner-pass-1");
                javafx.event.Event.fireEvent(revealed, new javafx.event.ActionEvent());
                assertNotNull(loggedIn.get());
                assertEquals("", password.getText());
                assertEquals("", revealed.getText());
                assertFalse(error.isVisible());
                return null;
            });
        }
    }

    @Test
    void entryScreensFitLightDarkAndTouchAndRenderForReview() throws Exception {
        try (AppServices app = app()) {
            fx(() -> {
                try {
                    for (ThemeManager.Mode mode : new ThemeManager.Mode[]{ThemeManager.Mode.LIGHT, ThemeManager.Mode.DARK}) {
                        ThemeManager.setMode(mode);
                        for (ThemeManager.Density density : new ThemeManager.Density[]{ThemeManager.Density.COMFORTABLE, ThemeManager.Density.TOUCH}) {
                            ThemeManager.setDensity(density);
                            for (int width : new int[]{1024, 1150}) {
                                int height = width == 1024 ? 680 : 740;
                                String suffix = mode.name().toLowerCase() + "-" + density.name().toLowerCase() + "-" + width;
                                for (boolean setup : new boolean[]{false, true}) {
                                    Parent view = setup ? new SetupView(app, user -> {}) : new LoginView(app, user -> {});
                                    layout(view, width, height);
                                    Node submit = view.lookup(setup ? "#setup-submit" : "#login-submit");
                                    var bounds = submit.localToScene(submit.getBoundsInLocal());
                                    assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= width, "Submit clipped horizontally");
                                    assertTrue(bounds.getMinY() >= 0 && bounds.getMaxY() <= height, "Submit clipped vertically");
                                    if (Boolean.getBoolean("screens")) {
                                        PngWriter.write(view.getScene().snapshot(null), Path.of("target", "screens",
                                                (setup ? "setup-" : "login-") + suffix + ".png"));
                                    }
                                }
                            }
                        }
                    }
                    LoginView compact = new LoginView(app, user -> {});
                    layout(compact, 640, 480);
                    Node artwork = compact.lookup(".auth-artwork");
                    assertTrue(artwork == null || !artwork.isVisible());
                    return null;
                } finally {
                    ThemeManager.setMode(ThemeManager.Mode.SYSTEM);
                    ThemeManager.setDensity(ThemeManager.Density.COMFORTABLE);
                }
            });
        }
    }

    private static void layout(Parent view, double width, double height) {
        ThemeManager.newScene(view, width, height);
        view.applyCss();
        view.resize(width, height);
        view.layout();
    }

    private static <T> T fx(java.util.concurrent.Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(60, TimeUnit.SECONDS);
    }
}
