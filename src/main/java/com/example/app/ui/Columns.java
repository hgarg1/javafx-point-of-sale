package com.example.app.ui;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextField;

import java.time.Instant;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/**
 * Table columns with typed values, so money, counts and dates sort by value rather than by their text.
 * ($9.00 sorts before $10.00, "9" before "10".)
 */
final class Columns {

    private Columns() {
    }

    /** Money in cents, shown as currency and right-aligned. */
    static <T> TableColumn<T, Long> money(String title, double width, ToLongFunction<T> cents) {
        TableColumn<T, Long> column = base(title, width);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cents.applyAsLong(cell.getValue())));
        column.setCellFactory(c -> new FormattedCell<>(Ui::money, true));
        return column;
    }

    /** A whole number, right-aligned. */
    static <T> TableColumn<T, Integer> whole(String title, double width, ToIntFunction<T> value) {
        TableColumn<T, Integer> column = base(title, width);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.applyAsInt(cell.getValue())));
        column.setCellFactory(c -> new FormattedCell<>(String::valueOf, true));
        return column;
    }

    static <T> TableColumn<T, Instant> dateTime(String title, double width, Function<T, Instant> value) {
        TableColumn<T, Instant> column = base(title, width);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        column.setCellFactory(c -> new FormattedCell<>(Ui::dateTime, false));
        return column;
    }

    static <T> TableColumn<T, String> text(String title, double width, Function<T, String> value) {
        TableColumn<T, String> column = base(title, width);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        column.setCellFactory(c -> new FormattedCell<>(s -> s, false));
        return column;
    }

    /**
     * A column with a text field in every row, bound to a property on the row. Each keystroke is written straight to
     * the row, so a value typed in one row is kept even when the user clicks a button before leaving the field.
     */
    static <T> TableColumn<T, String> input(String title, double width, Function<T, StringProperty> property) {
        TableColumn<T, String> column = base(title, width);
        column.setSortable(false);
        column.setCellValueFactory(cell -> property.apply(cell.getValue()));
        column.setCellFactory(c -> new InputCell<>(property));
        return column;
    }

    private static <T, V> TableColumn<T, V> base(String title, double width) {
        TableColumn<T, V> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        return column;
    }

    /** Shows a value through a formatter. */
    private static final class FormattedCell<T, V> extends TableCell<T, V> {

        private final Function<V, String> format;

        FormattedCell(Function<V, String> format, boolean rightAlign) {
            this.format = format;
            setAlignment(rightAlign ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(V item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : format.apply(item));
        }
    }

    /** A text field that writes its value to the row's property on every change. */
    private static final class InputCell<T> extends TableCell<T, String> {

        private final TextField field = new TextField();
        private final Function<T, StringProperty> property;
        private StringProperty bound;

        InputCell(Function<T, StringProperty> property) {
            this.property = property;
            field.setPrefColumnCount(6);
            field.textProperty().addListener((obs, was, now) -> {
                if (bound != null && !now.equals(bound.get())) {
                    bound.set(now);
                }
            });
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            T row = empty || getTableRow() == null ? null : getTableRow().getItem();
            bound = row == null ? null : property.apply(row);
            if (bound == null) {
                setGraphic(null);
                return;
            }
            field.setText(bound.get());
            setText(null);
            setGraphic(field);
        }
    }
}
