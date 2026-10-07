package com.example.app.ui;

import com.example.app.domain.PaymentMethod;
import com.example.app.domain.Shift;
import com.example.app.service.AppServices;
import com.example.app.service.ShiftService.ShiftClose;
import com.example.app.service.ShiftService.ShiftSummary;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Map;
import java.util.Optional;

/** Open the cash drawer for a shift, see live totals, and close the shift against the counted cash. */
final class ShiftView extends VBox {

    private final AppServices services;

    ShiftView(AppServices services) {
        this.services = services;
        setSpacing(12);
        setPadding(new Insets(16));
        rebuild();
    }

    private void rebuild() {
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
        TextField floatField = new TextField("200.00");
        Button open = new Button("Open shift");
        open.setOnAction(e -> Ui.attempt(() -> {
            long floatCents = Ui.parseMoney(floatField.getText(), "Opening float");
            services.shifts().openShift(floatCents);
            rebuild();
        }));
        getChildren().addAll(
                Ui.muted("No shift is open. Count the cash in the drawer and enter the opening float."),
                new HBox(8, new Label("Opening float"), floatField, open));
    }

    private void buildSummary(Shift shift) {
        ShiftSummary s = services.shifts().currentSummary();
        Map<PaymentMethod, Long> sales = s.salesByMethod();
        Map<PaymentMethod, Long> refunds = s.refundsByMethod();

        var grid = Ui.grid();
        int row = 0;
        grid.addRow(row++, new Label("Opened"), new Label(Ui.dateTime(shift.openedAt())));
        grid.addRow(row++, new Label("Opening float"), new Label(Ui.money(shift.openingFloatCents())));
        grid.addRow(row++, new Label("Sales"), new Label(s.salesCount() + " sale(s), " + Ui.money(s.grossSalesCents())));
        grid.addRow(row++, new Label("Tax on sales"), new Label(Ui.money(s.taxOnSalesCents())));
        grid.addRow(row++, new Label("Cash taken"), new Label(Ui.money(sales.getOrDefault(PaymentMethod.CASH, 0L))));
        grid.addRow(row++, new Label("Card taken"), new Label(Ui.money(sales.getOrDefault(PaymentMethod.CARD, 0L))));
        grid.addRow(row++, new Label("Refunds"), new Label(Ui.money(s.refundsCents())));
        grid.addRow(row, new Label("Expected cash in drawer"), boldLabel(Ui.money(s.expectedCashCents())));

        TextField counted = new TextField();
        counted.setPromptText("0.00");
        Button close = new Button("Close shift");
        close.setOnAction(e -> Ui.attempt(() -> {
            long countedCents = Ui.parseMoney(counted.getText(), "Counted cash");
            if (!Ui.confirm("Close this shift? Counted cash: " + Ui.money(countedCents) + ".")) {
                return;
            }
            ShiftClose result = services.shifts().closeShift(countedCents);
            Ui.showInfo("Shift closed.\nExpected: " + Ui.money(result.expectedCashCents())
                    + "\nCounted: " + Ui.money(result.countedCashCents())
                    + "\nVariance: " + Ui.money(result.varianceCents()));
            rebuild();
        }));

        getChildren().addAll(
                grid,
                new HBox(8, new Label("Counted cash"), counted, close));
    }

    private static Label boldLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }
}
