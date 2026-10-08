package com.example.app.ui;

import com.example.app.domain.Money;
import com.example.app.domain.MoneyFormat;
import com.example.app.domain.Tender;
import com.example.app.error.BusinessException;
import com.example.app.service.AppServices;
import com.example.app.service.CheckoutService.PaymentRequest;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Collects the payments for a sale: one row for each payment type that is turned on, in the order the store set. Payment
 * types other than cash are taken first, each for the amount typed and never more than is still owed. Cash covers whatever
 * is left unless a cash amount is given, and cash handed over above what is owed comes back as change.
 */
final class PaymentDialog {

    private PaymentDialog() {
    }

    /** The fields for one payment type: an amount, and the reference the type needs. Cash has no reference. */
    private record Row(Tender tender, TextField amount, TextField reference) {
    }

    /** The content of the dialog, with the fields the payments are read from. */
    private record Content(Node node, List<Row> rows) {
    }

    static Optional<List<PaymentRequest>> ask(AppServices services, long totalCents) {
        Content content = content(services, totalCents);
        AtomicReference<List<PaymentRequest>> result = new AtomicReference<>();
        boolean paid = FormDialog.run(null, "Payment", "Take payment for " + Ui.money(totalCents), content.node(),
                "Take payment", () -> result.set(build(content.rows(), totalCents)));
        return paid ? Optional.ofNullable(result.get()) : Optional.empty();
    }

    /** The dialog as the till shows it, built but not shown. The screenshot tests use it. */
    static Dialog<ButtonType> build(AppServices services, long totalCents) {
        Content content = content(services, totalCents);
        AtomicReference<List<PaymentRequest>> result = new AtomicReference<>();
        return FormDialog.build(null, "Payment", "Take payment for " + Ui.money(totalCents), content.node(),
                "Take payment", () -> result.set(build(content.rows(), totalCents)));
    }

    private static Content content(AppServices services, long totalCents) {
        GridPane grid = Ui.grid();
        grid.addRow(0, new Label("Total due"), boldLabel(Ui.money(totalCents)));
        grid.addRow(1, boldLabel("Payment type"), boldLabel("Amount"), boldLabel("Reference"));

        List<Row> rows = new ArrayList<>();
        int line = 2;
        for (Tender tender : services.tenders().offered()) {
            TextField amount = new TextField();
            amount.setPromptText(tender.isCash() ? "Blank = rest of total" : "0.00");
            TextField reference = new TextField();
            reference.setPromptText(tender.kind().referencePrompt());
            Row row = new Row(tender, amount, reference);
            rows.add(row);
            if (tender.isCash()) {
                grid.addRow(line++, new Label(tender.name() + " handed over"), amount, new Label(""));
            } else {
                grid.addRow(line++, new Label(tender.name()), amount, reference);
            }
        }
        VBox content = new VBox(12, grid, quickCash(totalCents, rows));
        return new Content(content, rows);
    }

    /**
     * Builds the payments. Payment types other than cash are taken first, in the order listed, and cannot exceed what is
     * still owed. Cash comes last: a blank cash amount covers the rest, and any cash handed over beyond that is change.
     * Throws a business error, which the dialog shows while keeping what was typed.
     */
    private static List<PaymentRequest> build(List<Row> rows, long totalCents) {
        List<PaymentRequest> payments = new ArrayList<>();
        long remaining = totalCents;

        for (Row row : rows) {
            if (row.tender().isCash()) {
                continue;
            }
            long cents = amountOf(row.amount(), row.tender().name(), remaining);
            if (cents > 0) {
                if (isBlank(row.reference().getText())) {
                    throw new BusinessException("Enter the reference for " + row.tender().name());
                }
                payments.add(new PaymentRequest(row.tender().code(), cents, row.reference().getText().trim()));
                remaining -= cents;
            }
        }

        boolean firstCash = true;
        for (Row row : rows) {
            if (!row.tender().isCash()) {
                continue;
            }
            long handed;
            if (isBlank(row.amount().getText())) {
                handed = firstCash ? remaining : 0;
            } else {
                handed = Ui.parseMoney(row.amount().getText(), row.tender().name() + " handed over");
                if (handed < 0) {
                    throw new BusinessException(row.tender().name() + " handed over cannot be negative");
                }
            }
            if (handed > 0) {
                payments.add(new PaymentRequest(row.tender().code(), handed, null));
            }
            firstCash = false;
        }
        if (payments.isEmpty()) {
            throw new BusinessException("Enter a payment amount");
        }
        return payments;
    }

    /** An amount typed for a payment type. Blank means nothing; it may not exceed what is still owed. */
    private static long amountOf(TextField field, String label, long owed) {
        long cents = isBlank(field.getText()) ? 0 : Ui.parseMoney(field.getText(), label);
        if (cents < 0 || cents > owed) {
            throw new BusinessException(label + " must be between $0.00 and " + new Money(owed).format());
        }
        return cents;
    }

    /** Buttons that fill the first cash amount: the exact remainder, or a common note. Amounts follow the store's format. */
    private static HBox quickCash(long totalCents, List<Row> rows) {
        HBox row = new HBox(6, new Label("Quick cash"));
        Row cash = rows.stream().filter(r -> r.tender().isCash()).findFirst().orElse(null);
        if (cash == null) {
            return row;
        }
        Button exact = new Button("Exact");
        exact.setOnAction(e -> cash.amount().setText(MoneyFormat.formatForInput(remainingAfterOthers(totalCents, rows))));
        row.getChildren().add(exact);
        for (long note : new long[]{500, 1_000, 2_000, 5_000, 10_000}) {
            Button choice = new Button(Ui.money(note));
            choice.setOnAction(e -> cash.amount().setText(MoneyFormat.formatForInput(note)));
            row.getChildren().add(choice);
        }
        return row;
    }

    /** What is still owed once the non-cash amounts typed so far are taken off. An amount that does not parse counts as nothing. */
    private static long remainingAfterOthers(long totalCents, List<Row> rows) {
        long remaining = totalCents;
        for (Row row : rows) {
            if (row.tender().isCash()) {
                continue;
            }
            try {
                remaining -= isBlank(row.amount().getText()) ? 0 : Ui.parseMoney(row.amount().getText(), row.tender().name());
            } catch (BusinessException e) {
                // An amount that does not parse yet counts as nothing; the build step reports it when the dialog is confirmed.
            }
        }
        return Math.max(0, remaining);
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static Label boldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("bold");
        return label;
    }
}
