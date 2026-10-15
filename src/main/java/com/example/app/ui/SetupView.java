package com.example.app.ui;

import com.example.app.domain.User;
import com.example.app.error.BusinessException;
import com.example.app.service.AppServices;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** The first screen on a new database: creates the owner (administrator) account. */
public final class SetupView extends AuthFrame {

    private static final Logger LOG = Logger.getLogger(SetupView.class.getName());

    public SetupView(AppServices services, Consumer<User> onDone) {
        this(services, onDone, new VBox(16));
    }

    private SetupView(AppServices services, Consumer<User> onDone, VBox fields) {
        super(services.storeName(), "YOUR STORE STARTS HERE", "Make it yours.",
                "Create your owner account to manage staff, products and settings.", fields,
                "You can add your team after setup.");

        TextField username = new TextField();
        username.setId("setup-username");
        username.setPromptText("Username (letters, digits, . - _)");
        PasswordField password = new PasswordField();
        password.setId("setup-password");
        password.setPromptText("Password (at least 8 characters)");
        PasswordField confirm = new PasswordField();
        confirm.setId("setup-confirm");
        confirm.setPromptText("Type the password again");
        Label message = Ui.error("");
        message.getStyleClass().add("auth-error");
        message.setMaxWidth(Double.MAX_VALUE);
        message.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        message.managedProperty().bind(message.textProperty().isNotEmpty());
        message.visibleProperty().bind(message.managedProperty());
        for (TextField input : java.util.List.of(username, password, confirm)) {
            input.getStyleClass().add("auth-input");
        }

        Button create = new Button("Create owner account");
        create.setId("setup-submit");
        create.getStyleClass().add("auth-submit");
        create.setDefaultButton(true);
        create.setMaxWidth(Double.MAX_VALUE);
        create.setOnAction(e -> {
            try {
                if (!password.getText().equals(confirm.getText())) {
                    throw new BusinessException("The two passwords do not match");
                }
                User owner = services.auth().completeSetup(username.getText(), password.getText());
                password.clear();
                confirm.clear();
                onDone.accept(owner);
            } catch (BusinessException ex) {
                message.setText(ex.getMessage());
            } catch (RuntimeException ex) {
                LOG.log(Level.SEVERE, "Account setup failed", ex);
                message.setText("Something went wrong. The details were written to the log.");
            }
        });
        confirm.setOnAction(e -> create.fire());

        fields.getChildren().addAll(field("Username", username), field("Password", password),
                field("Confirm password", confirm), message, create);
    }
}
