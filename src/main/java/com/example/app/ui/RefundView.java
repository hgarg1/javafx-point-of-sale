package com.example.app.ui;

import com.example.app.domain.Sale;
import com.example.app.domain.TenderTotal;
import com.example.app.error.ApprovalRequiredException;
import com.example.app.error.BusinessException;
import com.example.app.service.AppServices;
import com.example.app.service.RefundService.RefundLineRequest;
import com.example.app.service.RefundService.RefundableLine;
import com.example.app.domain.User;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Find a receipt, choose the quantity to return from each line, and record the refund. Anyone can process a refund;
 * a manager must approve it, and the approval dialog asks for one if the person at the till is not a manager.
 */
final class RefundView extends VBox implements Screen {

    /** One receipt line with the quantity typed for refund. The field writes straight to the row. */
    private static final class Line {
        final RefundableLine source;
        final StringProperty refundQty = new SimpleStringProperty("");

        Line(RefundableLine source) {
            this.source = source;
        }
    }

    private final AppServices services;
    private final TextField receiptNo = new TextField();
    private final Label saleInfo = Ui.muted("Enter a receipt number and press Find.");
    private final Label refundable = Ui.muted("");
    private final TableView<Line> lines = Ui.table();
    private final ChoiceBox<TenderTotal> method = new ChoiceBox<>(FXCollections.observableArrayList());
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

        lines.setPlaceholder(new Label("No receipt loaded"));
        lines.getColumns().addAll(
                Columns.text("Item", 260, l -> l.source.line().nameSnapshot()),
                Columns.whole("Sold", 60, l -> l.source.line().qty()),
                Columns.whole("Refunded", 80, l -> l.source.refundedQty()),
                Columns.whole("Left", 60, l -> l.source.remainingQty()),
                Columns.money("Line total", 100, l -> l.source.line().lineTotalCents()),
                Columns.input("Refund qty", 110, l -> l.refundQty));
        VBox.setVgrow(lines, Priority.ALWAYS);

        method.setConverter(new StringConverter<>() {
            @Override
            public String toString(TenderTotal tender) {
                return tender == null ? "" : tender.name();
            }

            @Override
            public TenderTotal fromString(String text) {
                return null;
            }
        });
        restock.setSelected(true);
        reason.setPromptText("Required, e.g. customer returned item");
        reason.setId("refund-reason");
        reason.setPrefColumnCount(36);
        reason.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(reason, Priority.ALWAYS);
        receiptNo.setPrefColumnCount(24);
        receiptNo.setMaxWidth(480);
        method.setMinWidth(210);
        Button submit = new Button("Record refund");
        submit.getStyleClass().add("primary");
        submit.setOnAction(e -> Ui.attempt(this::submit));

        HBox lookup = new HBox(8, new Label("Receipt"), receiptNo, find);
        var options = Ui.actions(new Label("Pay back to"), method, restock);
        HBox reasonRow = new HBox(8, new Label("Reason"), reason);
        reasonRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        lookup.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(receiptNo, Priority.ALWAYS);
        ((Label) reasonRow.getChildren().getFirst()).setLabelFor(reason);
        getChildren().addAll(Ui.heading("Refunds"), lookup, saleInfo, refundable, lines, options, reasonRow,
                new HBox(8, submit), message);
    }

    @Override
    public void onShow() {
        if (sale != null) {
            Ui.attempt(this::reloadLines);
        }
    }

    private void find() {
        String number = receiptNo.getText();
        sale = services.refunds().findSaleByReceipt(number)
                .orElseThrow(() -> new BusinessException("No receipt " + number.trim()));
        message.setText("");
        reloadLines();
    }

    /** Rebuilds the receipt lines with their refunded amounts as they are now. */
    private void reloadLines() {
        List<Line> rows = new ArrayList<>();
        for (RefundableLine line : services.refunds().refundableLines(sale.id())) {
            rows.add(new Line(line));
        }
        lines.setItems(FXCollections.observableArrayList(rows));
        saleInfo.setText("Receipt " + sale.receiptNo() + " from " + Ui.dateTime(sale.createdAt())
                + ", total " + Ui.money(sale.totalCents()) + ".");

        List<TenderTotal> left = services.refunds().refundableByTender(sale.id());
        refundable.setText(left.isEmpty() ? "Nothing was paid on this receipt."
                : "Still refundable: " + left.stream()
                        .map(t -> t.name() + " " + Ui.money(t.cents()))
                        .collect(Collectors.joining(", ")) + ".");
        List<TenderTotal> choices = left.stream().filter(t -> t.cents() > 0).toList();
        method.getItems().setAll(choices);
        method.setValue(choices.isEmpty() ? null : choices.get(0));
    }

    private void submit() {
        if (sale == null) {
            throw new BusinessException("Find a receipt first");
        }
        List<RefundLineRequest> requests = new ArrayList<>();
        for (Line row : lines.getItems()) {
            String text = row.refundQty.get() == null ? "" : row.refundQty.get().trim();
            if (text.isEmpty() || text.equals("0")) {
                continue;
            }
            int qty = Ui.parseWhole(text, "Refund quantity for " + row.source.line().nameSnapshot());
            requests.add(new RefundLineRequest(row.source.line().id(), qty));
        }
        if (requests.isEmpty()) {
            throw new BusinessException("Enter the quantity to refund for at least one item");
        }
        if (method.getValue() == null) {
            throw new BusinessException("Nothing is left to refund on this receipt");
        }
        if (!Ui.confirm("Refund on receipt " + sale.receiptNo() + " to " + method.getValue().name().toLowerCase() + "?")) {
            return;
        }

        Long approverId = null;
        long refundId;
        while (true) {
            try {
                refundId = services.refunds().refund(sale.id(), requests, method.getValue().tender(), reason.getText(),
                        restock.isSelected(), approverId);
                break;
            } catch (ApprovalRequiredException e) {
                Optional<User> approver = ApproverDialog.ask(services, e.getMessage());
                if (approver.isEmpty()) {
                    return;
                }
                approverId = approver.get().id();
            }
        }
        message.setText("Refund #" + refundId + " recorded.");
        reason.clear();
        reloadLines();
    }
}
