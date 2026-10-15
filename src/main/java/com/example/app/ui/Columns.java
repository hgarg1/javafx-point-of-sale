package com.example.app.ui;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;

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
        column.setMinWidth(95);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cents.applyAsLong(cell.getValue())));
        column.setCellFactory(c -> new FormattedCell<>(Ui::money, true));
        return column;
    }

    /** A whole number, right-aligned. */
    static <T> TableColumn<T, Integer> whole(String title, double width, ToIntFunction<T> value) {
        TableColumn<T, Integer> column = base(title, width);
        column.setMinWidth(Math.max(55, Math.min(width, 100)));
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.applyAsInt(cell.getValue())));
        column.setCellFactory(c -> new FormattedCell<>(String::valueOf, true));
        return column;
    }

    static <T> TableColumn<T, Instant> dateTime(String title, double width, Function<T, Instant> value) {
        TableColumn<T, Instant> column = base(title, width);
        column.setMinWidth(155);
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
        column.setMinWidth(145);
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
            setTooltip(empty || item == null ? null : new Tooltip(format.apply(item)));
        }
    }

    /** A text field that writes its value to the row's property on every change. */
    private static final class InputCell<T> extends TableCell<T, String> {

        private final TextField field = new TextField();
        private final Function<T, StringProperty> property;
        private StringProperty bound;
        private boolean syncing;
        private final javafx.beans.value.ChangeListener<String> sourceChanged = (obs, was, now) -> showValue(now);

        InputCell(Function<T, StringProperty> property) {
            this.property = property;
            field.setPrefColumnCount(10);
            field.setMinWidth(0);
            field.setMaxWidth(Double.MAX_VALUE);
            field.getStyleClass().add("table-input");
            field.prefWidthProperty().bind(javafx.beans.binding.Bindings.max(0, widthProperty().subtract(16)));
            field.textProperty().addListener((obs, was, now) -> {
                if (!syncing && bound != null && !now.equals(bound.get())) {
                    bound.set(now);
                }
            });
            indexProperty().addListener((obs, was, now) -> bindRow(isEmpty()));
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            bindRow(empty);
        }

        private void bindRow(boolean empty) {
            var table = getTableView();
            int index = getIndex();
            T row = empty || table == null || index < 0 || index >= table.getItems().size()
                    ? null : table.getItems().get(index);
            StringProperty next = row == null ? null : property.apply(row);
            if (bound != next) {
                if (bound != null) { bound.removeListener(sourceChanged); }
                bound = next;
                if (bound != null) { bound.addListener(sourceChanged); }
            }
            if (bound == null) {
                setGraphic(null);
                return;
            }
            field.setAccessibleText(getTableColumn().getText());
            showValue(bound.get());
            setText(null);
            setGraphic(field);
        }

        private void showValue(String value) {
            syncing = true;
            try { field.setText(value == null ? "" : value); }
            finally { syncing = false; }
        }
    }
}
