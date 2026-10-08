package com.example.app.ui;

import com.example.app.service.AppServices;
import com.example.app.util.PasswordHasher;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.TabPane;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Renders every screen, in light and dark, to {@code target/screens/*.png} so the UI can be reviewed by eye.
 * Off by default; run with {@code mvn test -Dtest=ScreenshotTest -Dscreens=true}.
 */
@EnabledIfSystemProperty(named = "screens", matches = "true")
class ScreenshotTest {

    private static final Path OUT = Path.of("target", "screens");
    private static final double WIDTH = 1280;
    private static final double HEIGHT = 800;
    private static final String[] SCREENS = {"Checkout", "Shift", "Refunds", "Products", "Stock", "Purchasing", "Reports",
            "Users", "Settings"};
    /** Screens with a second tab worth reviewing, rendered under the given name after the first. */
    private static final Map<String, String> SECOND_TABS = Map.of(
            "Reports", "reports-sales",
            "Purchasing", "purchasing-suggestions");

    @BeforeAll
    static void startToolkit() {
        try {
            Platform.startup(() -> {
            });
        } catch (IllegalStateException alreadyStarted) {
            // Shared toolkit from another test.
        }
    }

    @Test
    void renderEveryScreenInLightAndDark() throws Exception {
        try (AppServices app = new AppServices("jdbc:h2:mem:" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1", new PasswordHasher(1_000))) {
            DemoData.seed(app);
            app.auth().login("owner", "owner-pass-1");

            onFxThread(() -> {
                for (ThemeManager.Mode mode : new ThemeManager.Mode[]{ThemeManager.Mode.LIGHT, ThemeManager.Mode.DARK}) {
                    ThemeManager.setMode(mode);
                    String suffix = mode == ThemeManager.Mode.DARK ? "dark" : "light";
                    render(new LoginView(app, user -> {
                    }), "login-" + suffix);
                    for (String screen : SCREENS) {
                        AppShell shell = new AppShell(app, UiPrefs.defaults(), () -> {
                        });
                        shell.navigate(screen);
                        render(shell, screen.toLowerCase() + "-" + suffix);
                        if (SECOND_TABS.containsKey(screen)) {
                            // A root can only be in one scene, so the second tab gets a fresh shell.
                            AppShell second = new AppShell(app, UiPrefs.defaults(), () -> {
                            });
                            second.navigate(screen);
                            ((TabPane) second.lookup(".tab-pane")).getSelectionModel().select(1);
                            render(second, SECOND_TABS.get(screen) + "-" + suffix);
                        }
                    }
                }
                return null;
            });
        }
    }

    private static void render(Parent root, String name) {
        render(root, name, WIDTH, HEIGHT);
    }

    private static void render(Parent root, String name, double width, double height) {
        try {
            var scene = ThemeManager.newScene(root, width, height);
            root.applyCss();
            root.layout();
            WritableImage image = scene.snapshot(null);
            PngWriter.write(image, OUT.resolve(name + ".png"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write screenshot " + name, e);
        }
    }


    private static <T> T onFxThread(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(120, TimeUnit.SECONDS);
    }
}
