package com.example.app.ui;

import com.example.app.domain.User;
import com.example.app.error.BusinessException;
import com.example.app.service.AppServices;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** The first screen on a new database: creates the owner (administrator) account. */
public final class SetupView extends VBox {

    private static final Logger LOG = Logger.getLogger(SetupView.class.getName());

    public SetupView(AppServices services, Consumer<User> onDone) {
        setSpacing(10);
        setAlignment(Pos.CENTER);
        setPadding(new Insets(40));

        Label title = Ui.heading("Welcome");
        Label intro = Ui.muted("Create the owner account. This person can manage staff, products and settings.");
        intro.setMaxWidth(340);

        TextField username = new TextField();
        username.setPromptText("Username (letters, digits, . - _)");
        PasswordField password = new PasswordField();
        password.setPromptText("Password (at least 8 characters)");
        PasswordField confirm = new PasswordField();
        confirm.setPromptText("Type the password again");
        Label message = Ui.error("");

        Button create = new Button("Create owner account");
        create.setDefaultButton(true);
        create.setMaxWidth(Double.MAX_VALUE);
        create.setOnAction(e -> {
            try {
                if (!password.getText().equals(confirm.getText())) {
                    throw new BusinessException("The two passwords do not match");
                }
                User owner = services.auth().completeSetup(username.getText(), password.getText());
                onDone.accept(owner);
            } catch (BusinessException ex) {
                message.setText(ex.getMessage());
            } catch (RuntimeException ex) {
                LOG.log(Level.SEVERE, "Account setup failed", ex);
                message.setText("Something went wrong. The details were written to the log.");
            }
        });
        confirm.setOnAction(e -> create.fire());

        getChildren().addAll(title, intro, username, password, confirm, create, message);
    }
}
