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

    private ReceiptDialog() {
    }

    static void show(String receiptText) {
        ButtonType save = new ButtonType("Save as .txt", ButtonBar.ButtonData.LEFT);
        TextArea text = new TextArea(receiptText);
        text.setEditable(false);
        text.setStyle("-fx-font-family: 'Consolas', monospace;");
        text.setPrefRowCount(22);
        text.setPrefColumnCount(44);

        while (true) {
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("Receipt");
            dialog.setHeaderText("Sale complete");
            dialog.getDialogPane().setContent(text);
            dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CLOSE);

            Optional<ButtonType> choice = dialog.showAndWait();
            if (choice.isEmpty() || choice.get() != save) {
                return;
            }
            saveAsText(receiptText);
        }
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
