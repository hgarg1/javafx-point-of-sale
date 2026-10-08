package com.example.app;

import com.example.app.db.MigrationException;
import com.example.app.domain.User;
import com.example.app.service.AppServices;
import com.example.app.ui.AppShell;
import com.example.app.ui.ChangePasswordDialog;
import com.example.app.ui.LoginView;
import com.example.app.ui.SetupView;
import com.example.app.ui.ThemeManager;
import com.example.app.ui.UiPrefs;
import com.example.app.util.AppLog;
import com.example.app.util.PasswordHasher;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public class App extends Application {

    private static final Logger LOG = Logger.getLogger(App.class.getName());
    /** H2's error for a database file that another process already has open. */
    private static final int DATABASE_ALREADY_OPEN = 90020;

    private AppServices services;
    private UiPrefs prefs;
    private Stage stage;
    private Path home;

    public App() {
    }

    @Override
    public void start(Stage stage) throws IOException {
        this.stage = stage;
        this.home = homeFolder();
        try {
            AppLog.install(home.resolve("logs"));
        } catch (IOException e) {
            System.err.println("The log file cannot be written: " + e.getMessage());
        }
        this.prefs = UiPrefs.load(home);
        ThemeManager.setMode(prefs.themeMode());
        ThemeManager.setDensity(prefs.density());

        try {
            services = new AppServices(jdbcUrl(home), new PasswordHasher(), home.resolve("backups"));
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "The database could not be opened", e);
            Alert alert = new Alert(Alert.AlertType.ERROR, startupMessage(e), ButtonType.OK);
            alert.setHeaderText(null);
            ThemeManager.apply(alert.getDialogPane());
            alert.showAndWait();
            Platform.exit();
            return;
        }

        stage.setTitle(services.storeName() + " - Point of Sale");
        stage.setMinWidth(1024);
        stage.setMinHeight(680);
        Optional<double[]> saved = prefs.window();
        if (saved.isPresent()) {
            double[] b = saved.get();
            stage.setX(b[0]);
            stage.setY(b[1]);
            stage.setWidth(b[2]);
            stage.setHeight(b[3]);
        } else {
            stage.setWidth(1150);
            stage.setHeight(740);
        }
        stage.setOnHidden(e -> rememberWindow());

        if (services.auth().needsSetup()) {
            showSetup();
        } else {
            showLogin();
        }
        stage.show();
    }

    @Override
    public void stop() {
        if (prefs != null) {
            rememberWindow();
        }
        if (services == null) {
            return;
        }
        try {
            services.backups().backupIfDue(Duration.ofHours(24));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "The automatic backup on exit failed", e);
        }
        services.close();
    }

    private void rememberWindow() {
        if (prefs != null && stage != null && !stage.isFullScreen() && !stage.isIconified()) {
            prefs.saveWindow(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
            prefs.save();
        }
    }

    private void showSetup() {
        stage.setScene(ThemeManager.newScene(new SetupView(services, this::onLoggedIn), 460, 440));
    }

    private void showLogin() {
        stage.setScene(ThemeManager.newScene(new LoginView(services, this::onLoggedIn), 420, 360));
    }

    private void onLoggedIn(User user) {
        if (user.mustChangePassword() && !ChangePasswordDialog.show(services, true)) {
            services.auth().logout();
            showLogin();
            return;
        }
        stage.setScene(ThemeManager.newScene(new AppShell(services, prefs, this::showLogin), stage.getWidth(), stage.getHeight()));
    }

    /** Everything the app writes lives under one folder in the user's home directory. */
    static Path homeFolder() {
        return Path.of(System.getProperty("user.home"), ".cashier-app");
    }

    static String jdbcUrl(Path home) throws IOException {
        Path data = home.resolve("data");
        Files.createDirectories(data);
        return "jdbc:h2:file:" + data.resolve("pos").toAbsolutePath().toString().replace('\\', '/');
    }

    /** A plain explanation for the most likely startup failures, with the log location. */
    private String startupMessage(RuntimeException e) {
        Path logs = home.resolve("logs");
        if (isAlreadyOpen(e)) {
            return "Point of Sale is already open in another window. Close that window first, then start this one again.";
        }
        if (e instanceof MigrationException) {
            return "The data could not be upgraded to this version. A backup was taken before the upgrade, in:\n"
                    + home.resolve("backups") + "\n\nThe details are in the log:\n" + logs;
        }
        return "The data file could not be opened. The details are in the log:\n" + logs;
    }

    private static boolean isAlreadyOpen(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getErrorCode() == DATABASE_ALREADY_OPEN) {
                return true;
            }
        }
        return false;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
