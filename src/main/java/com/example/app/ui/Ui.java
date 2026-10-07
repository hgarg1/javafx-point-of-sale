package com.example.app.ui;

import com.example.app.domain.Money;
import com.example.app.error.AccessDeniedException;
import com.example.app.error.BusinessException;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.GridPane;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

/** Shared UI helpers: error dialogs, input parsing, and small layout builders. */
final class Ui {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private Ui() {
    }

    /**
     * Runs an action from a control handler. Business and permission errors are shown to the user;
     * anything unexpected is logged and shown as a generic error.
     */
    static void attempt(Runnable action) {
        try {
            action.run();
        } catch (BusinessException | AccessDeniedException e) {
            showError(e.getMessage());
        } catch (RuntimeException e) {
            e.printStackTrace();
            showError("Something went wrong: " + e.getMessage());
        }
    }

    static void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    static void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    static boolean confirm(String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.YES, ButtonType.NO);
        alert.setHeaderText(null);
        return alert.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }

    static long parseMoney(String text, String label) {
        try {
            return Money.parseCents(text == null ? "" : text);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(label + " must be an amount such as 12.50");
        }
    }

    static int parseWhole(String text, String label) {
        try {
            return Integer.parseInt(text == null ? "" : text.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(label + " must be a whole number");
        }
    }

    /** Parses a percentage such as "8.25" into basis points (825). */
    static int parsePercentToBp(String text, String label) {
        try {
            return new BigDecimal(text.trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
        } catch (RuntimeException e) {
            throw new BusinessException(label + " must be a percentage such as 8.25");
        }
    }

    static String money(long cents) {
        return new Money(cents).format();
    }

    static String dateTime(Instant instant) {
        return instant == null ? "" : DATE_TIME.format(instant.atZone(ZoneId.systemDefault()));
    }

    static String percent(int basisPoints) {
        return plainPercent(basisPoints) + "%";
    }

    /** "10" for 1000 basis points, without a percent sign. Used to pre-fill input boxes. */
    static String plainPercent(int basisPoints) {
        return BigDecimal.valueOf(basisPoints, 2).stripTrailingZeros().toPlainString();
    }

    static <T> TableColumn<T, String> column(String title, double width, Function<T, String> value) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyStringWrapper(value.apply(cell.getValue())));
        return column;
    }

    static <T> TableView<T> table() {
        TableView<T> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        return table;
    }

    static Label heading(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        return label;
    }

    static Label muted(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: #666666;");
        label.setWrapText(true);
        return label;
    }

    static Label error(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: #b00020;");
        label.setWrapText(true);
        return label;
    }

    static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        return grid;
    }
}
