package com.example.app.ui;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextArea;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Shows a finished receipt, with an option to save it as a text file. */
final class ReceiptDialog {

    private static final ButtonType SAVE = new ButtonType("Save as .txt", ButtonBar.ButtonData.LEFT);

    private ReceiptDialog() {
    }

    static void show(String receiptText) {
        while (true) {
            Optional<ButtonType> choice = build(receiptText).showAndWait();
            if (choice.isEmpty() || choice.get() != SAVE) {
                return;
            }
            saveAsText(receiptText);
        }
    }

    /** The receipt dialog, built but not shown. The screenshot tests use it. */
    static Dialog<ButtonType> build(String receiptText) {
        TextArea text = new TextArea(receiptText);
        text.setEditable(false);
        text.getStyleClass().add("receipt");
        text.setPrefRowCount(22);
        text.setPrefColumnCount(44);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Receipt");
        dialog.setResizable(true);
        dialog.setHeaderText("Sale complete");
        dialog.getDialogPane().setContent(text);
        dialog.getDialogPane().getButtonTypes().addAll(SAVE, ButtonType.CLOSE);
        ThemeManager.apply(dialog.getDialogPane());
        return dialog;
    }

    private static void saveAsText(String receiptText) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save receipt");
        chooser.setInitialFileName("receipt.txt");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text files", "*.txt"));
        File file = chooser.showSaveDialog(null);
        if (file == null) {
            return;
        }
        try {
            Files.writeString(Path.of(file.toURI()), receiptText);
            Ui.showInfo("Receipt saved to " + file.getAbsolutePath());
        } catch (IOException e) {
            Ui.showError("Could not save the receipt: " + e.getMessage());
        }
    }
}
