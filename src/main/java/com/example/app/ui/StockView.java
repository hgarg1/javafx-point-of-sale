package com.example.app.ui;

import com.example.app.domain.MovementType;
import com.example.app.domain.Product;
import com.example.app.domain.StockMovement;
import com.example.app.error.BusinessException;
import com.example.app.service.AppServices;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Stock on hand, receipts, waste, adjustments and stock take. Every change is a movement row. */
final class StockView extends BorderPane {

    private final AppServices services;
    private final TableView<Product> products = Ui.table();
    private final TableView<StockMovement> history = Ui.table();
    private final CheckBox lowStockOnly = new CheckBox("Show low stock only");
    private final Label message = Ui.muted("");
    private final Label historyTitle = Ui.heading("Movements");

    StockView(AppServices services) {
        this.services = services;

        products.getColumns().addAll(
                Ui.<Product>column("SKU", 90, Product::sku),
                Ui.<Product>column("Name", 220, Product::name),
                Ui.<Product>column("On hand", 80, p -> String.valueOf(p.stockQty())),
                Ui.<Product>column("Reorder at", 90, p -> String.valueOf(p.reorderLevel())),
                Ui.<Product>column("Status", 80, p -> p.isLowStock() ? "LOW" : "OK"));
        products.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> loadHistory(now));
        VBox.setVgrow(products, Priority.ALWAYS);

        history.getColumns().addAll(
                Ui.<StockMovement>column("When", 130, m -> Ui.dateTime(m.createdAt())),
                Ui.<StockMovement>column("Type", 110, m -> m.type().name()),
                Ui.<StockMovement>column("Qty", 60, m -> String.valueOf(m.qtyDelta())),
                Ui.<StockMovement>column("Reason", 220, m -> m.reason() == null ? "" : m.reason()));

        Button receive = new Button("Receive...");
        receive.setOnAction(e -> move(MovementType.RECEIVE));
        Button adjust = new Button("Adjust...");
        adjust.setOnAction(e -> move(MovementType.ADJUST));
        Button waste = new Button("Waste...");
        waste.setOnAction(e -> move(MovementType.WASTE));
        Button stockTake = new Button("Stock take...");
        stockTake.setOnAction(e -> Ui.attempt(this::stockTake));
        lowStockOnly.setOnAction(e -> reload());
        HBox actions = new HBox(8, receive, adjust, waste, stockTake, lowStockOnly);

        VBox top = new VBox(8, Ui.heading("Stock"), actions, products, message);
        top.setPadding(new Insets(0, 0, 12, 0));
        VBox.setVgrow(products, Priority.ALWAYS);
        VBox bottom = new VBox(6, historyTitle, history);
        VBox.setVgrow(history, Priority.ALWAYS);

        setTop(top);
        setCenter(bottom);
        setPadding(new Insets(12));
        reload();
    }

    private void reload() {
        List<Product> items = lowStockOnly.isSelected()
                ? services.inventory().listLowStock()
                : services.inventory().listProducts();
        products.setItems(FXCollections.observableArrayList(items));
    }

    private void loadHistory(Product product) {
        if (product == null) {
            history.setItems(FXCollections.observableArrayList());
            historyTitle.setText("Movements");
            return;
        }
        historyTitle.setText("Movements for " + product.name());
        history.setItems(FXCollections.observableArrayList(services.inventory().movementsFor(product.id())));
    }

    private Product selectedProduct() {
        Product selected = products.getSelectionModel().getSelectedItem();
        if (selected == null) {
            throw new BusinessException("Select a product first");
        }
        return selected;
    }

    private void move(MovementType type) {
        Ui.attempt(() -> {
            Product product = selectedProduct();
            Optional<MovementInput> input = askMovement(type, product.name());
            if (input.isEmpty()) {
                return;
            }
            services.inventory().recordMovement(product.id(), type,
                    Ui.parseWhole(input.get().qty(), "Quantity"), input.get().reason());
            message.setText(describe(type, product.name()));
            reload();
            loadHistory(product);
        });
    }

    private static String describe(MovementType type, String name) {
        return switch (type) {
            case RECEIVE -> "Received stock for " + name + ".";
            case ADJUST -> "Adjusted stock for " + name + ".";
            case WASTE -> "Recorded waste for " + name + ".";
            default -> "Saved.";
        };
    }

    private static Optional<MovementInput> askMovement(MovementType type, String productName) {
        boolean needsReason = type != MovementType.RECEIVE;
        TextField qty = new TextField();
        qty.setPromptText(type == MovementType.ADJUST ? "Use -3 to remove 3, 3 to add 3" : "Quantity");
        TextField reason = new TextField();
        reason.setPromptText(needsReason ? "Required, e.g. damaged, recount" : "Optional, e.g. invoice number");

        var grid = Ui.grid();
        grid.addRow(0, new Label("Quantity"), qty);
        grid.addRow(1, new Label(needsReason ? "Reason" : "Reason (optional)"), reason);

        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(titleFor(type));
        dialog.setHeaderText(titleFor(type) + ": " + productName);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        Optional<ButtonType> choice = dialog.showAndWait();
        if (choice.isEmpty() || choice.get() != save) {
            return Optional.empty();
        }
        return Optional.of(new MovementInput(qty.getText(), reason.getText()));
    }

    private static String titleFor(MovementType type) {
        return switch (type) {
            case RECEIVE -> "Receive stock";
            case ADJUST -> "Adjust stock";
            case WASTE -> "Record waste";
            default -> "Stock movement";
        };
    }

    private void stockTake() {
        List<Product> all = services.inventory().listProducts();
        if (all.isEmpty()) {
            throw new BusinessException("There are no products to count");
        }
        TableView<CountRow> table = Ui.table();
        table.setEditable(true);
        table.setPrefSize(560, 420);
        table.setItems(FXCollections.observableArrayList(all.stream().map(CountRow::new).toList()));

        TableColumn<CountRow, String> counted = new TableColumn<>("Counted");
        counted.setPrefWidth(110);
        counted.setEditable(true);
        counted.setCellValueFactory(cell -> cell.getValue().counted);
        counted.setCellFactory(TextFieldTableCell.forTableColumn());
        counted.setOnEditCommit(e -> e.getRowValue().counted.set(e.getNewValue()));
        table.getColumns().addAll(
                Ui.<CountRow>column("SKU", 90, r -> r.product.sku()),
                Ui.<CountRow>column("Name", 240, r -> r.product.name()),
                Ui.<CountRow>column("System", 80, r -> String.valueOf(r.product.stockQty())),
                counted);

        ButtonType apply = new ButtonType("Apply counts", ButtonBar.ButtonData.OK_DONE);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Stock take");
        dialog.setHeaderText("Type the counted quantity for each product. Only changed counts are adjusted.");
        dialog.getDialogPane().setContent(table);
        dialog.getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);

        Optional<ButtonType> choice = dialog.showAndWait();
        if (choice.isEmpty() || choice.get() != apply) {
            return;
        }
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (CountRow row : table.getItems()) {
            counts.put(row.product.id(), Ui.parseWhole(row.counted.get(), "Count for " + row.product.name()));
        }
        int adjusted = services.inventory().stockTake(counts);
        message.setText("Stock take complete: " + adjusted + " product(s) adjusted.");
        reload();
    }

    private record MovementInput(String qty, String reason) {
    }

    /** A row in the stock take grid. The counted value is editable. */
    private static final class CountRow {
        final Product product;
        final StringProperty counted;

        CountRow(Product product) {
            this.product = product;
            this.counted = new SimpleStringProperty(String.valueOf(product.stockQty()));
        }
    }
}
