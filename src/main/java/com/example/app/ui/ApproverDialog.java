package com.example.app.ui;

import com.example.app.domain.User;
import com.example.app.error.BusinessException;
import com.example.app.service.AppServices;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

import java.util.Optional;

/** Asks a manager to approve an override by entering their own credentials. The cashier stays logged in. */
final class ApproverDialog {

    private ApproverDialog() {
    }

    static Optional<User> ask(AppServices services, String reason) {
        ButtonType approve = new ButtonType("Approve", ButtonBar.ButtonData.OK_DONE);
        while (true) {
            TextField username = new TextField();
            username.setPromptText("Manager username");
            PasswordField password = new PasswordField();
            password.setPromptText("Manager password");

            var grid = Ui.grid();
            grid.addRow(0, new Label("Username"), username);
            grid.addRow(1, new Label("Password"), password);

            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("Manager approval");
            dialog.setHeaderText(reason + ". A manager must approve this.");
            dialog.getDialogPane().setContent(grid);
            dialog.getDialogPane().getButtonTypes().addAll(approve, ButtonType.CANCEL);

            Optional<ButtonType> choice = dialog.showAndWait();
            if (choice.isEmpty() || choice.get() != approve) {
                return Optional.empty();
            }
            try {
                return Optional.of(services.auth().verifyApprover(username.getText(), password.getText()));
            } catch (BusinessException e) {
                Ui.showError(e.getMessage());
            }
        }
    }
}
