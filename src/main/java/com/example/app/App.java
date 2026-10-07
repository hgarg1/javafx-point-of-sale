package com.example.app;

import com.example.app.domain.User;
import com.example.app.service.AppServices;
import com.example.app.ui.AppShell;
import com.example.app.ui.ChangePasswordDialog;
import com.example.app.ui.LoginView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class App extends Application {

    private AppServices services;
    private Stage stage;

    @Override
    public void start(Stage stage) throws IOException {
        this.stage = stage;
        this.services = new AppServices(defaultJdbcUrl());
        stage.setTitle(services.storeName() + " - Point of Sale");
        showLogin();
        stage.show();
    }

    @Override
    public void stop() {
        if (services != null) {
            services.close();
        }
    }

    private void showLogin() {
        LoginView login = new LoginView(services, this::onLoggedIn);
        stage.setScene(new Scene(login, 420, 360));
    }

    private void onLoggedIn(User user) {
        if (user.mustChangePassword() && !ChangePasswordDialog.show(services, true)) {
            services.auth().logout();
            showLogin();
            return;
        }
        stage.setScene(new Scene(new AppShell(services, this::showLogin), 1150, 740));
    }

    /** The data file lives in the user's home folder, so it survives updates to the app. */
    static String defaultJdbcUrl() throws IOException {
        Path dir = Path.of(System.getProperty("user.home"), ".cashier-app", "data");
        Files.createDirectories(dir);
        return "jdbc:h2:file:" + dir.resolve("pos").toAbsolutePath().toString().replace('\\', '/');
    }

    public static void main(String[] args) {
        launch(args);
    }
}
