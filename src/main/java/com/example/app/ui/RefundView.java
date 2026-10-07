package com.example.app.ui;

import com.example.app.domain.PaymentMethod;
import com.example.app.domain.Sale;
import com.example.app.error.BusinessException;
import com.example.app.service.AppServices;
import com.example.app.service.RefundService.RefundLineRequest;
import com.example.app.service.RefundService.RefundableLine;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/** Look up a receipt by number, choose how many of each item to refund, and record the refund. */
final class RefundView extends VBox {

    /** One line of the receipt, with the quantity the manager has typed for refund. */
    private static final class RefundRow {
        final RefundableLine line;
        final StringProperty refundQty = new SimpleStringProperty("0");

        RefundRow(RefundableLine line) {
            this.line = line;
        }
    }

    private final AppServices services;
    private final TextField receiptNo = new TextField();
    private final Label saleInfo = Ui.muted("Enter a receipt number and press Find.");
    private final TableView<RefundRow> lines = Ui.table();
    private final ChoiceBox<PaymentMethod> method = new ChoiceBox<>(FXCollections.observableArrayList(PaymentMethod.CASH, PaymentMethod.CARD));
    private final TextField reason = new TextField();
    private final CheckBox restock = new CheckBox("Return items to stock");
    private final Label message = Ui.muted("");
    private Sale sale;

    RefundView(AppServices services) {
        this.services = services;
        setSpacing(12);
        setPadding(new Insets(16));

        receiptNo.setPromptText("Receipt number, e.g. R001000");
        receiptNo.setOnAction(e -> Ui.attempt(this::find));
        Button find = new Button("Find");
        find.setOnAction(e -> Ui.attempt(this::find));

        TableColumn<RefundRow, String> refundColumn = new TableColumn<>("Refund qty");
        refundColumn.setPrefWidth(110);
        refundColumn.setEditable(true);
        refundColumn.setCellValueFactory(cell -> cell.getValue().refundQty);
        refundColumn.setCellFactory(TextFieldTableCell.forTableColumn());
        refundColumn.setOnEditCommit(e -> e.getRowValue().refundQty.set(e.getNewValue()));
        lines.setEditable(true);
        lines.setPlaceholder(new Label("No receipt loaded"));
        lines.getColumns().addAll(
                Ui.<RefundRow>column("Item", 260, r -> r.line.line().nameSnapshot()),
                Ui.<RefundRow>column("Sold", 60, r -> String.valueOf(r.line.line().qty())),
                Ui.<RefundRow>column("Refunded", 80, r -> String.valueOf(r.line.refundedQty())),
                Ui.<RefundRow>column("Left", 60, r -> String.valueOf(r.line.remainingQty())),
                Ui.<RefundRow>column("Line total", 90, r -> Ui.money(r.line.line().lineTotalCents())),
                refundColumn);
        VBox.setVgrow(lines, Priority.ALWAYS);

        method.setValue(PaymentMethod.CASH);
        restock.setSelected(true);
        reason.setPromptText("Required, e.g. customer returned item");
        Button submit = new Button("Record refund");
        submit.setStyle("-fx-font-weight: bold;");
        submit.setOnAction(e -> Ui.attempt(this::submit));

        HBox lookup = new HBox(8, new Label("Receipt"), receiptNo, find);
        HBox options = new HBox(12, new Label("Paid back by"), method, restock);
        HBox reasonRow = new HBox(8, new Label("Reason"), reason);
        getChildren().addAll(Ui.heading("Refunds"), lookup, saleInfo, lines, options, reasonRow,
                new HBox(8, submit), message);
    }

    private void find() {
        String number = receiptNo.getText();
        Sale found = services.refunds().findSaleByReceipt(number)
                .orElseThrow(() -> new BusinessException("No receipt " + number.trim()));
        sale = found;
        List<RefundRow> rows = new ArrayList<>();
        for (RefundableLine line : services.refunds().refundableLines(found.id())) {
            rows.add(new RefundRow(line));
        }
        lines.setItems(FXCollections.observableArrayList(rows));
        saleInfo.setText("Receipt " + found.receiptNo() + " from " + Ui.dateTime(found.createdAt())
                + ", total " + Ui.money(found.totalCents()) + ".");
        message.setText("");
    }

    private void submit() {
        if (sale == null) {
            throw new BusinessException("Find a receipt first");
        }
        List<RefundLineRequest> requests = new ArrayList<>();
        for (RefundRow row : lines.getItems()) {
            String text = row.refundQty.get() == null ? "" : row.refundQty.get().trim();
            if (text.isEmpty() || text.equals("0")) {
                continue;
            }
            int qty = Ui.parseWhole(text, "Refund quantity for " + row.line.line().nameSnapshot());
            requests.add(new RefundLineRequest(row.line.line().id(), qty));
        }
        if (requests.isEmpty()) {
            throw new BusinessException("Enter the quantity to refund for at least one item");
        }
        if (!Ui.confirm("Record this refund on receipt " + sale.receiptNo() + "?")) {
            return;
        }
        long refundId = services.refunds().refund(sale.id(), requests, method.getValue(), reason.getText(),
                restock.isSelected());
        message.setText("Refund #" + refundId + " recorded.");
        reason.clear();
        find();
    }
}
