package com.example.app.ui;

import com.example.app.error.BusinessException;
import com.example.app.service.AppServices;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;

/** Asks for the current and new password. Returns true when changed. */
public final class ChangePasswordDialog {

    private ChangePasswordDialog() {
    }

    /**
     * When {@code forced} is true the user must change the password to go on; cancelling returns false and the
     * caller should log them out.
     */
    public static boolean show(AppServices services, boolean forced) {
        return FormDialog.showAndWait(build(services, forced));
    }

    /** The dialog, built but not shown. */
    static Dialog<ButtonType> build(AppServices services, boolean forced) {
        PasswordField current = new PasswordField();
        PasswordField next = new PasswordField();
        PasswordField confirm = new PasswordField();

        var grid = Ui.grid();
        grid.addRow(0, new Label("Current password"), current);
        grid.addRow(1, new Label("New password"), next);
        grid.addRow(2, new Label("Confirm new password"), confirm);

        String header = forced
                ? "Your password was set by an administrator. Choose a new one to continue."
                : "Change your password";
        return FormDialog.build(null, "Change password", header, grid, "Save", () -> {
            if (!next.getText().equals(confirm.getText())) {
                throw new BusinessException("The new passwords do not match");
            }
            services.auth().changeOwnPassword(current.getText(), next.getText());
        });
    }
}
