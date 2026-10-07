package com.example.app.ui;

import com.example.app.error.BusinessException;
import com.example.app.service.AppServices;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;

import java.util.Optional;

public final class ChangePasswordDialog {

    private ChangePasswordDialog() {
    }

    /**
     * Asks for the current and new password. Returns true when changed. When {@code forced} is true,
     * cancelling returns false and the caller should log the user out.
     */
    public static boolean show(AppServices services, boolean forced) {
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        while (true) {
            PasswordField current = new PasswordField();
            PasswordField next = new PasswordField();
            PasswordField confirm = new PasswordField();

            var grid = Ui.grid();
            grid.addRow(0, new Label("Current password"), current);
            grid.addRow(1, new Label("New password"), next);
            grid.addRow(2, new Label("Confirm new password"), confirm);

            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("Change password");
            dialog.setHeaderText(forced
                    ? "Your password was set by an administrator. Choose a new one to continue."
                    : "Change your password");
            dialog.getDialogPane().setContent(grid);
            dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

            Optional<ButtonType> choice = dialog.showAndWait();
            if (choice.isEmpty() || choice.get() != save) {
                return false;
            }
            if (!next.getText().equals(confirm.getText())) {
                Ui.showError("The new passwords do not match");
                continue;
            }
            try {
                services.auth().changeOwnPassword(current.getText(), next.getText());
                return true;
            } catch (BusinessException e) {
                Ui.showError(e.getMessage());
            }
        }
    }
}
