package com.example.app.ui;

import com.example.app.domain.User;
import com.example.app.error.BusinessException;
import com.example.app.service.AppServices;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.application.Platform;
import java.util.logging.Level;
import java.util.logging.Logger;

import java.util.function.Consumer;

public final class LoginView extends AuthFrame {
    private static final Logger LOG = Logger.getLogger(LoginView.class.getName());

    public LoginView(AppServices services, Consumer<User> onLoggedIn) {
        this(services, onLoggedIn, new VBox(20));
    }

    private LoginView(AppServices services, Consumer<User> onLoggedIn, VBox fields) {
        super(services.storeName(), "WELCOME TO " + services.storeName().toUpperCase(java.util.Locale.ROOT),
                "Welcome back.", "Sign in to get your store ready for the day.", fields,
                "Need access? Ask your store administrator.");

        TextField username = new TextField();
        username.setId("login-username");
        username.setPromptText("Enter your username");
        username.getStyleClass().add("auth-input");
        PasswordField password = new PasswordField();
        password.setId("login-password");
        password.setPromptText("Enter your password");
        TextField visiblePassword = new TextField();
        visiblePassword.setPromptText("Enter your password");
        visiblePassword.textProperty().bindBidirectional(password.textProperty());
        password.getStyleClass().add("auth-password-input");
        visiblePassword.getStyleClass().add("auth-password-input");
        visiblePassword.setVisible(false);
        visiblePassword.setManaged(false);
        StackPane entry = new StackPane(password, visiblePassword);
        HBox.setHgrow(entry, Priority.ALWAYS);
        Button reveal = new Button("Show");
        reveal.setId("login-reveal");
        reveal.setAccessibleText("Show password");
        reveal.getStyleClass().add("auth-reveal");
        HBox passwordBox = new HBox(4, entry, reveal);
        passwordBox.getStyleClass().add("auth-password-box");
        reveal.setOnAction(e -> {
            boolean showing = !visiblePassword.isVisible();
            visiblePassword.setVisible(showing);
            visiblePassword.setManaged(showing);
            password.setVisible(!showing);
            password.setManaged(!showing);
            reveal.setText(showing ? "Hide" : "Show");
            reveal.setAccessibleText(showing ? "Hide password" : "Show password");
        });
        Label message = Ui.error("");
        message.setId("login-error");
        message.setMaxWidth(Double.MAX_VALUE);
        message.setMinHeight(Region.USE_PREF_SIZE);
        message.getStyleClass().add("auth-error");
        message.managedProperty().bind(message.textProperty().isNotEmpty());
        message.visibleProperty().bind(message.managedProperty());
        Button login = new Button("Sign in  →");
        login.setId("login-submit");
        login.getStyleClass().add("auth-submit");
        login.setDefaultButton(true);
        login.setMaxWidth(Double.MAX_VALUE);

        login.setOnAction(e -> {
            if (username.getText().isBlank() || password.getText().isBlank()) {
                message.setText("Enter your username and password to continue.");
                if (username.getText().isBlank()) { username.requestFocus(); }
                else { (visiblePassword.isVisible() ? visiblePassword : password).requestFocus(); }
                return;
            }
            login.setDisable(true);
            try {
                User user = services.auth().login(username.getText(), password.getText());
                password.clear();
                message.setText("");
                onLoggedIn.accept(user);
            } catch (BusinessException ex) {
                message.setText(ex.getMessage());
            } catch (RuntimeException ex) {
                LOG.log(Level.SEVERE, "Sign in failed", ex);
                message.setText("Sign in could not be completed. Please try again.");
            } finally {
                login.setDisable(false);
            }
        });
        username.setOnAction(e -> (visiblePassword.isVisible() ? visiblePassword : password).requestFocus());
        password.setOnAction(e -> login.fire());
        visiblePassword.setOnAction(e -> login.fire());

        VBox passwordField = field("Password", passwordBox);
        ((Label) passwordField.getChildren().getFirst()).setLabelFor(password);
        fields.getChildren().addAll(field("Username", username), passwordField, message, login);
        sceneProperty().addListener((obs, was, now) -> {
            if (now != null) { Platform.runLater(username::requestFocus); }
        });
    }
}
