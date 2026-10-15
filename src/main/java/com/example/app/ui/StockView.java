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
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stock on hand, receipts, waste, adjustments and stock take. Every change is a movement row. */
final class StockView extends BorderPane implements Screen {

    /** A row in the stock take: the product and the count typed for it. */
    private static final class CountRow {
        final Product product;
        final StringProperty counted;

        CountRow(Product product) {
            this.product = product;
            this.counted = new SimpleStringProperty(String.valueOf(product.stockQty()));
        }
    }

    private final AppServices services;
    private final TableView<Product> products = Ui.table();
    private final TableView<StockMovement> history = Ui.table();
    private final CheckBox lowStockOnly = new CheckBox("Show low stock only");
    private final Label message = Ui.muted("");
    private final Label historyTitle = Ui.heading("Movements");
    private final Map<Long, String> namesByUserId = new HashMap<>();

    StockView(AppServices services) {
        this.services = services;

        products.getColumns().addAll(
                Columns.text("SKU", 90, Product::sku),
                Columns.text("Name", 220, Product::name),
                Columns.whole("On hand", 80, Product::stockQty),
                Columns.whole("Reorder at", 90, Product::reorderLevel),
                Columns.text("Status", 80, p -> p.isLowStock() ? "LOW" : "OK"));
        products.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> loadHistory(now));
        VBox.setVgrow(products, Priority.ALWAYS);

        history.getColumns().addAll(
                Columns.dateTime("When", 130, StockMovement::createdAt),
                Columns.text("Type", 110, m -> m.type().name()),
                Columns.whole("Qty", 60, StockMovement::qtyDelta),
                Columns.text("Reason", 200, m -> m.reason() == null ? "" : m.reason()),
                Columns.text("By", 90, m -> namesByUserId.getOrDefault(m.userId(), "")));

        Button receive = new Button("Receive...");
        receive.setOnAction(e -> Ui.attempt(() -> move(MovementType.RECEIVE)));
        Button adjust = new Button("Adjust...");
        adjust.setOnAction(e -> Ui.attempt(() -> move(MovementType.ADJUST)));
        Button waste = new Button("Waste...");
        waste.setOnAction(e -> Ui.attempt(() -> move(MovementType.WASTE)));
        Button stockTake = new Button("Stock take...");
        stockTake.setOnAction(e -> Ui.attempt(this::stockTake));
        lowStockOnly.setOnAction(e -> Ui.attempt(this::reload));
        var actions = Ui.actions(receive, adjust, waste, stockTake, lowStockOnly);

        VBox top = new VBox(8, Ui.heading("Stock"), actions, products, message);
        top.setPadding(new Insets(0, 0, 12, 0));
        VBox bottom = new VBox(6, historyTitle, history);
        VBox.setVgrow(history, Priority.ALWAYS);

        var split = Ui.split(.55, top, bottom);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        top.setMinHeight(180);
        bottom.setMinHeight(160);
        setCenter(split);
        setPadding(new Insets(12));
        onShow();
    }

    @Override
    public void onShow() {
        reload();
    }

    /** Reloads the list and keeps the product that was selected, so an action does not clear the selection. */
    private void reload() {
        Long selectedId = selectedProduct().map(Product::id).orElse(null);
        List<Product> items = lowStockOnly.isSelected()
                ? services.inventory().listLowStock()
                : services.inventory().listProducts();
        products.setItems(FXCollections.observableArrayList(items));
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id() == (selectedId == null ? -1 : selectedId)) {
                products.getSelectionModel().select(i);
                break;
            }
        }
    }

    private void loadHistory(Product product) {
        if (product == null) {
            history.setItems(FXCollections.observableArrayList());
            historyTitle.setText("Movements");
            return;
        }
        historyTitle.setText("Movements for " + product.name());
        List<StockMovement> movements = services.inventory().movementsFor(product.id());
        namesByUserId.clear();
        for (StockMovement m : movements) {
            namesByUserId.computeIfAbsent(m.userId(), services.auth()::usernameOf);
        }
        history.setItems(FXCollections.observableArrayList(movements));
    }

    private java.util.Optional<Product> selectedProduct() {
        return java.util.Optional.ofNullable(products.getSelectionModel().getSelectedItem());
    }

    private void move(MovementType type) {
        Product product = selectedProduct().orElseThrow(() -> new BusinessException("Select a product first"));
        boolean needsReason = type != MovementType.RECEIVE;
        TextField qty = new TextField();
        qty.setPromptText(type == MovementType.ADJUST ? "Use -3 to remove 3, 3 to add 3" : "Quantity");
        TextField reason = new TextField();
        reason.setPromptText(needsReason ? "Required, e.g. damaged, recount" : "Optional, e.g. invoice number");

        var grid = Ui.grid();
        grid.addRow(0, new Label("Quantity"), qty);
        grid.addRow(1, new Label(needsReason ? "Reason" : "Reason (optional)"), reason);

        boolean saved = FormDialog.run(null, titleFor(type), titleFor(type) + ": " + product.name(), grid, "Save",
                () -> services.inventory().recordMovement(product.id(), type,
                        Ui.parseWhole(qty.getText(), "Quantity"), reason.getText()));
        if (saved) {
            message.setText(describe(type, product.name()));
            reload();
            loadHistory(selectedProduct().orElse(null));
        }
    }

    private static String describe(MovementType type, String name) {
        return switch (type) {
            case RECEIVE -> "Received stock for " + name + ".";
            case ADJUST -> "Adjusted stock for " + name + ".";
            case WASTE -> "Recorded waste for " + name + ".";
            default -> "Saved.";
        };
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
        table.setItems(FXCollections.observableArrayList(all.stream().map(CountRow::new).toList()));
        table.getColumns().addAll(
                Columns.text("SKU", 90, r -> r.product.sku()),
                Columns.text("Name", 240, r -> r.product.name()),
                Columns.whole("System", 80, r -> r.product.stockQty()),
                Columns.input("Counted", 110, r -> r.counted));
        table.setPrefSize(600, 420);

        boolean applied = FormDialog.run(null, "Stock take",
                "Type the counted quantity for each product. Only changed counts are adjusted.", table,
                "Apply counts", () -> {
                    Map<Long, Integer> counts = new LinkedHashMap<>();
                    for (CountRow row : table.getItems()) {
                        counts.put(row.product.id(),
                                Ui.parseWhole(row.counted.get(), "Count for " + row.product.name()));
                    }
                    int adjusted = services.inventory().stockTake(counts);
                    message.setText("Stock take complete: " + adjusted + " product(s) adjusted.");
                });
        if (applied) {
            reload();
            loadHistory(selectedProduct().orElse(null));
        }
    }
}
