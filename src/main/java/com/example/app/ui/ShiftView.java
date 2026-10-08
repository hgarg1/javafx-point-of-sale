package com.example.app.ui;

import com.example.app.domain.MoneyFormat;
import com.example.app.domain.Role;
import com.example.app.domain.Shift;
import com.example.app.domain.TenderTotal;
import com.example.app.domain.User;
import com.example.app.service.AppServices;
import com.example.app.service.ShiftService.ShiftClose;
import com.example.app.service.ShiftService.ShiftSummary;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Optional;

/**
 * One drawer per terminal. Open the shift with a float; while it is open, see what was taken. The expected cash is
 * deliberately hidden until the drawer has been counted ("blind count"), so the count is an honest check.
 */
final class ShiftView extends VBox implements Screen {

    private final AppServices services;

    ShiftView(AppServices services) {
        this.services = services;
        setSpacing(12);
        setPadding(new Insets(16));
        onShow();
    }

    @Override
    public void onShow() {
        getChildren().clear();
        getChildren().add(Ui.heading("Shift"));
        Optional<Shift> open = services.shifts().currentShift();
        if (open.isEmpty()) {
            buildOpenForm();
        } else {
            buildSummary(open.get());
        }
    }

    private void buildOpenForm() {
        TextField floatField = new TextField(MoneyFormat.formatForInput(20_000));
        HBox quick = new HBox(6, new Label("Quick amounts"));
        for (long amount : new long[]{10_000, 20_000, 30_000, 50_000}) {
            Button choice = new Button(Ui.money(amount));
            choice.setOnAction(e -> floatField.setText(MoneyFormat.formatForInput(amount)));
            quick.getChildren().add(choice);
        }
        Button open = new Button("Open shift");
        open.getStyleClass().add("primary");
        open.setOnAction(e -> Ui.attempt(() -> {
            long floatCents = Ui.parseMoney(floatField.getText(), "Opening float");
            services.shifts().openShift(floatCents);
            onShow();
        }));
        getChildren().addAll(
                Ui.muted("No shift is open on this terminal. Count the cash in the drawer and enter the opening float."),
                quick,
                new HBox(8, new Label("Opening float"), floatField, open));
    }

    private void buildSummary(Shift shift) {
        ShiftSummary s = services.shifts().currentSummary();

        String openedBy = services.auth().usernameOf(shift.userId());
        var grid = Ui.grid();
        int row = 0;
        grid.addRow(row++, new Label("Opened"), new Label(Ui.dateTime(shift.openedAt()) + " by " + openedBy));
        grid.addRow(row++, new Label("Opening float"), new Label(Ui.money(shift.openingFloatCents())));
        grid.addRow(row++, new Label("Sales"), new Label(s.salesCount() + " sale(s), " + Ui.money(s.grossSalesCents())));
        grid.addRow(row++, new Label("Tax on sales"), new Label(Ui.money(s.taxOnSalesCents())));
        for (TenderTotal taken : s.salesByTender()) {
            grid.addRow(row++, new Label(taken.name() + " taken"), new Label(Ui.money(taken.cents())));
        }
        grid.addRow(row++, new Label("Refunds"), new Label(Ui.money(s.refundsCents())));
        grid.addRow(row, new Label("Voids"), new Label(Ui.money(s.voidsCents())));

        TextField counted = new TextField();
        counted.setPromptText("0.00");
        Button close = new Button("Count and close shift");
        close.setOnAction(e -> Ui.attempt(() -> closeShift(counted)));

        Label who = Ui.muted(canClose(shift)
                ? "Count the drawer, then close the shift. The expected amount is shown after the count."
                : "Only " + openedBy + " or a manager can close this shift.");
        getChildren().addAll(
                grid,
                who,
                new HBox(8, new Label("Counted cash"), counted, close));
    }

    private void closeShift(TextField counted) {
        long countedCents = Ui.parseMoney(counted.getText(), "Counted cash");
        if (!Ui.confirm("Close the shift with " + Ui.money(countedCents) + " counted? This cannot be undone.")) {
            return;
        }
        ShiftSummary summary = services.shifts().currentSummary();
        ShiftClose result = services.shifts().closeShift(countedCents);
        String cashier = services.session().requireUser().username();
        ReceiptDialog.show(ReceiptFormatter.zReport(services.storeName(), summary, result, cashier));
        onShow();
    }

    private boolean canClose(Shift shift) {
        User user = services.session().requireUser();
        return shift.userId() == user.id() || user.role().atLeast(Role.MANAGER);
    }
}
