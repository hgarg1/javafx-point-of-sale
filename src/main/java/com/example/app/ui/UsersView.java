package com.example.app.ui;

import com.example.app.domain.Role;
import com.example.app.domain.User;
import com.example.app.error.BusinessException;
import com.example.app.service.AppServices;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** User accounts. Admins only. Admins cannot change their own role or disable themselves. */
final class UsersView extends VBox implements Screen {

    private final AppServices services;
    private final TableView<User> table = Ui.table();
    private final Label message = Ui.muted("");

    UsersView(AppServices services) {
        this.services = services;
        setSpacing(10);
        setPadding(new Insets(16));

        table.getColumns().addAll(
                Columns.text("Username", 200, User::username),
                Columns.text("Role", 120, u -> u.role().name()),
                Columns.text("Active", 80, u -> u.active() ? "Yes" : "No"),
                Columns.text("Must change password", 160, u -> u.mustChangePassword() ? "Yes" : "No"));
        VBox.setVgrow(table, Priority.ALWAYS);

        Button create = new Button("New user...");
        create.setOnAction(e -> Ui.attempt(this::createUser));
        Button role = new Button("Change role...");
        role.setOnAction(e -> Ui.attempt(this::changeRole));
        Button toggle = new Button("Enable / disable");
        toggle.setOnAction(e -> Ui.attempt(this::toggleActive));
        Button reset = new Button("Reset password...");
        reset.setOnAction(e -> Ui.attempt(this::resetPassword));

        getChildren().addAll(Ui.heading("Users"), Ui.actions(create, role, toggle, reset), table, message);
        onShow();
    }

    @Override
    public void onShow() {
        table.setItems(FXCollections.observableArrayList(services.auth().listUsers()));
    }

    private User selected() {
        User user = table.getSelectionModel().getSelectedItem();
        if (user == null) {
            throw new BusinessException("Select a user first");
        }
        return user;
    }

    private void createUser() {
        TextField username = new TextField();
        PasswordField password = new PasswordField();
        password.setPromptText("At least 8 characters");
        ChoiceBox<Role> role = new ChoiceBox<>(FXCollections.observableArrayList(Role.values()));
        role.setValue(Role.CASHIER);

        var grid = Ui.grid();
        grid.addRow(0, new Label("Username"), username);
        grid.addRow(1, new Label("Temporary password"), password);
        grid.addRow(2, new Label("Role"), role);

        boolean created = FormDialog.run(null, "New user", "The user must change this password at first login.",
                grid, "Create", () -> services.auth().createUser(username.getText(), password.getText(), role.getValue()));
        if (created) {
            message.setText("Created user " + username.getText().trim().toLowerCase() + ".");
            onShow();
        }
    }

    private void changeRole() {
        User user = selected();
        ChoiceDialog<Role> dialog = new ChoiceDialog<>(user.role(), Role.values());
        dialog.setTitle("Change role");
        dialog.setHeaderText("New role for " + user.username());
        dialog.setContentText("Role:");
        dialog.showAndWait().ifPresent(newRole -> {
            services.auth().changeRole(user.id(), newRole);
            message.setText(user.username() + " is now " + newRole + ".");
            onShow();
        });
    }

    private void toggleActive() {
        User user = selected();
        boolean enable = !user.active();
        if (!Ui.confirm("Really " + (enable ? "enable " : "disable ") + user.username() + "?")) {
            return;
        }
        services.auth().setActive(user.id(), enable);
        message.setText(user.username() + " " + (enable ? "enabled" : "disabled") + ".");
        onShow();
    }

    private void resetPassword() {
        User user = selected();
        PasswordField password = new PasswordField();
        password.setPromptText("New temporary password");
        var grid = Ui.grid();
        grid.addRow(0, new Label("Temporary password"), password);

        boolean reset = FormDialog.run(null, "Reset password",
                "Set a temporary password for " + user.username() + ". They must change it at next login.",
                grid, "Reset", () -> services.auth().resetPassword(user.id(), password.getText()));
        if (reset) {
            message.setText("Password reset for " + user.username() + ".");
            onShow();
        }
    }
}
