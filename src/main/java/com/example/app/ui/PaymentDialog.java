package com.example.app.ui;

import com.example.app.domain.PaymentMethod;
import com.example.app.error.BusinessException;
import com.example.app.service.CheckoutService.PaymentRequest;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Collects card and/or cash for a sale. Card is taken first; cash covers the rest unless an amount is given. */
final class PaymentDialog {

    private PaymentDialog() {
    }

    static Optional<List<PaymentRequest>> ask(long totalCents) {
        ButtonType takePayment = new ButtonType("Take payment", ButtonBar.ButtonData.OK_DONE);
        while (true) {
            TextField card = new TextField();
            card.setPromptText("0.00");
            TextField cardNumber = new TextField();
            cardNumber.setPromptText("Simulated: numbers ending 0002 are declined");
            TextField cash = new TextField();
            cash.setPromptText("Blank = rest of total");

            var grid = Ui.grid();
            grid.addRow(0, new Label("Total due"), boldLabel(Ui.money(totalCents)));
            grid.addRow(1, new Label("Card amount"), card);
            grid.addRow(2, new Label("Card number"), cardNumber);
            grid.addRow(3, new Label("Cash handed over"), cash);

            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("Payment");
            dialog.setHeaderText("Take payment for " + Ui.money(totalCents));
            dialog.getDialogPane().setContent(grid);
            dialog.getDialogPane().getButtonTypes().addAll(takePayment, ButtonType.CANCEL);

            Optional<ButtonType> choice = dialog.showAndWait();
            if (choice.isEmpty() || choice.get() != takePayment) {
                return Optional.empty();
            }
            try {
                return Optional.of(build(totalCents, card.getText(), cardNumber.getText(), cash.getText()));
            } catch (BusinessException e) {
                Ui.showError(e.getMessage());
            }
        }
    }

    private static List<PaymentRequest> build(long totalCents, String cardText, String cardNumber, String cashText) {
        long cardCents = isBlank(cardText) ? 0 : Ui.parseMoney(cardText, "Card amount");
        if (cardCents < 0 || cardCents > totalCents) {
            throw new BusinessException("Card amount must be between $0.00 and the total due");
        }
        long remaining = totalCents - cardCents;
        long cashCents = isBlank(cashText) ? remaining : Ui.parseMoney(cashText, "Cash handed over");
        if (cashCents < 0) {
            throw new BusinessException("Cash handed over cannot be negative");
        }

        List<PaymentRequest> tenders = new ArrayList<>();
        if (cardCents > 0) {
            if (isBlank(cardNumber)) {
                throw new BusinessException("Enter the card number");
            }
            tenders.add(new PaymentRequest(PaymentMethod.CARD, cardCents, cardNumber.trim()));
        }
        if (cashCents > 0) {
            tenders.add(new PaymentRequest(PaymentMethod.CASH, cashCents, null));
        }
        if (tenders.isEmpty()) {
            throw new BusinessException("Enter a payment amount");
        }
        return tenders;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static Label boldLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }
}
