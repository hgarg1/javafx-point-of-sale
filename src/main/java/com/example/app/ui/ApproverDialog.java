package com.example.app.ui;

import com.example.app.domain.User;
import com.example.app.service.AppServices;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Asks a manager to approve an override by entering their own credentials. The person at the till stays logged in. */
final class ApproverDialog {

    private ApproverDialog() {
    }

    static Optional<User> ask(AppServices services, String reason) {
        TextField username = new TextField();
        username.setPromptText("Manager username");
        PasswordField password = new PasswordField();
        password.setPromptText("Manager password");

        var grid = Ui.grid();
        grid.addRow(0, new Label("Username"), username);
        grid.addRow(1, new Label("Password"), password);

        AtomicReference<User> approver = new AtomicReference<>();
        boolean approved = FormDialog.run(null, "Manager approval", reason + ". A manager must approve this.", grid,
                "Approve", () -> approver.set(services.auth().verifyApprover(username.getText(), password.getText())));
        return approved ? Optional.ofNullable(approver.get()) : Optional.empty();
    }
}
