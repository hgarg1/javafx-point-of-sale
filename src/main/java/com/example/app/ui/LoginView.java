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

public final class LoginView extends VBox {

    public LoginView(AppServices services, Consumer<User> onLoggedIn) {
        setSpacing(10);
        setAlignment(Pos.CENTER);
        setPadding(new Insets(40));

        Label title = Ui.heading(services.storeName());
        TextField username = new TextField();
        username.setPromptText("Username");
        PasswordField password = new PasswordField();
        password.setPromptText("Password");
        Label message = Ui.error("");
        Button login = new Button("Log in");
        login.setDefaultButton(true);
        login.setMaxWidth(Double.MAX_VALUE);

        login.setOnAction(e -> {
            try {
                User user = services.auth().login(username.getText(), password.getText());
                password.clear();
                message.setText("");
                onLoggedIn.accept(user);
            } catch (BusinessException ex) {
                message.setText(ex.getMessage());
            }
        });
        password.setOnAction(e -> login.fire());

        getChildren().addAll(title, username, password, login, message);
    }
}
