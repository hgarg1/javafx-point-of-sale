package com.example.app.ui;

import com.example.app.domain.MoneyFormat;
import com.example.app.domain.Product;
import com.example.app.domain.PurchaseStatus;
import com.example.app.domain.Supplier;
import com.example.app.error.BusinessException;
import com.example.app.service.AppServices;
import com.example.app.service.PurchasingService.OrderDetail;
import com.example.app.service.PurchasingService.OrderLine;
import com.example.app.service.PurchasingService.OrderSummary;
import com.example.app.service.PurchasingService.ReceiveLine;
import com.example.app.service.PurchasingService.Suggestion;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Buying: purchase orders from draft to received, and the reorder suggestions that start them. Managers only.
 * Stock only changes when a delivery is booked in, so nothing here touches stock directly.
 */
final class PurchasingView extends BorderPane implements Screen {

    private final AppServices services;
    private final ComboBox<String> statusFilter = new ComboBox<>();
    private final TableView<OrderSummary> orders = Ui.table();
    private final TableView<OrderLine> lines = Ui.table();
    private final TableView<Suggestion> suggestions = Ui.table();
    private final Label header = Ui.heading("Choose an order");
    private final Label note = Ui.muted("");
    private final Button newOrder = new Button("New order...");
    private final Button addItem = new Button("Add item...");
    private final Button removeItem = new Button("Remove item");
    private final Button send = new Button("Send order");
    private final Button receive = new Button("Receive delivery...");
    private final Button cancel = new Button("Cancel order");
    private final Button export = new Button("Export CSV...");
    private final Tab ordersTab = new Tab("Orders");
    private final Tab suggestionsTab = new Tab("Reorder suggestions");
    private final TabPane tabs = new TabPane(ordersTab, suggestionsTab);

    PurchasingView(AppServices services) {
        this.services = services;
        setPadding(new Insets(16));

        orders.getColumns().addAll(
                Columns.text("Order", 80, o -> "PO " + o.id()),
                Columns.text("Supplier", 160, OrderSummary::supplierName),
                Columns.text("Status", 120, o -> label(o.status())),
                Columns.whole("Ordered", 80, OrderSummary::qtyOrdered),
                Columns.whole("Received", 90, OrderSummary::qtyReceived),
                Columns.money("Value", 100, OrderSummary::totalCents),
                Columns.dateTime("Created", 140, OrderSummary::createdAt));
        orders.setPlaceholder(new Label("No orders yet. Start one with New order, or draft them from the reorder suggestions."));
        orders.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> showOrder(now));

        lines.getColumns().addAll(
                Columns.text("SKU", 90, OrderLine::sku),
                Columns.text("Item", 200, OrderLine::name),
                Columns.whole("Ordered", 80, OrderLine::qtyOrdered),
                Columns.whole("Received", 80, OrderLine::qtyReceived),
                Columns.whole("To come", 80, OrderLine::qtyRemaining),
                Columns.money("Unit cost", 100, OrderLine::unitCostCents),
                Columns.money("Line total", 110, OrderLine::lineTotalCents));
        lines.setPlaceholder(new Label("No items on this order."));

        suggestions.getColumns().addAll(
                Columns.text("Supplier", 160, Suggestion::supplierName),
                Columns.text("SKU", 90, Suggestion::sku),
                Columns.text("Item", 200, Suggestion::name),
                Columns.whole("On hand", 80, Suggestion::onHand),
                Columns.whole("Order", 80, Suggestion::suggestedQty),
                Columns.money("Unit cost", 100, Suggestion::unitCostCents));
        suggestions.setPlaceholder(new Label("Nothing is low on stock."));

        statusFilter.getItems().add("All statuses");
        for (PurchaseStatus status : PurchaseStatus.values()) {
            statusFilter.getItems().add(label(status));
        }
        statusFilter.getSelectionModel().selectedIndexProperty().addListener((obs, was, now) -> Ui.attempt(this::loadOrders));

        newOrder.getStyleClass().add("primary");
        newOrder.setOnAction(e -> Ui.attempt(this::createOrder));
        addItem.setOnAction(e -> Ui.attempt(this::addLine));
        removeItem.setOnAction(e -> Ui.attempt(this::removeLine));
        send.setOnAction(e -> Ui.attempt(this::sendOrder));
        receive.setOnAction(e -> Ui.attempt(this::receiveDelivery));
        cancel.setOnAction(e -> Ui.attempt(this::cancelOrder));
        export.setOnAction(e -> Ui.attempt(this::exportOrder));
        setActions(null);

        // Stacked rather than side by side, so the columns and the action buttons have the full width of the window.
        HBox filters = new HBox(8, new Label("Show"), statusFilter, newOrder);
        HBox actions = new HBox(8, addItem, removeItem, send, receive, cancel, export);
        orders.setPrefHeight(230);
        lines.setPrefHeight(260);
        VBox ordersPane = new VBox(8, filters, orders, header, note, lines, actions);
        VBox.setVgrow(lines, Priority.ALWAYS);
        ordersPane.setPadding(new Insets(12, 0, 0, 0));

        ordersTab.setContent(ordersPane);
        ordersTab.setClosable(false);
        suggestionsTab.setContent(suggestionsPane());
        suggestionsTab.setClosable(false);
        suggestionsTab.setOnSelectionChanged(e -> {
            if (suggestionsTab.isSelected()) {
                Ui.attempt(this::loadSuggestions);
            }
        });
        VBox.setVgrow(tabs, Priority.ALWAYS);

        setTop(Ui.heading("Purchasing"));
        setCenter(tabs);
        statusFilter.getSelectionModel().selectFirst();
        onShow();
    }

    @Override
    public void onShow() {
        Ui.attempt(this::loadOrders);
        Ui.attempt(this::loadSuggestions);
    }

    private Node suggestionsPane() {
        Button draft = new Button("Draft orders from these");
        draft.getStyleClass().add("primary");
        draft.setOnAction(e -> Ui.attempt(this::draftFromSuggestions));
        VBox pane = new VBox(8,
                Ui.muted("Products at or below their reorder level and not already on an open order. Each supplier gets one draft order. "
                        + "Products with no supplier are left out until one is set."),
                draft, suggestions);
        pane.setPadding(new Insets(12, 0, 0, 0));
        VBox.setVgrow(suggestions, Priority.ALWAYS);
        return pane;
    }

    /** The label shown for a status everywhere in this screen. */
    static String label(PurchaseStatus status) {
        return switch (status) {
            case DRAFT -> "Draft";
            case ORDERED -> "Ordered";
            case PARTIAL -> "Part received";
            case RECEIVED -> "Received";
            case CANCELLED -> "Cancelled";
        };
    }

    private void loadOrders() {
        OrderSummary keep = orders.getSelectionModel().getSelectedItem();
        orders.setItems(FXCollections.observableArrayList(services.purchasing().list(statusFilterValue())));
        if (keep != null) {
            selectOrder(keep.id());
        } else {
            showOrder(null);
        }
    }

    private void loadSuggestions() {
        suggestions.setItems(FXCollections.observableArrayList(services.purchasing().reorderSuggestions()));
    }

    /** Shows the lines of the chosen order, and enables only the actions that order's status allows. */
    private void showOrder(OrderSummary summary) {
        if (summary == null) {
            header.setText("Choose an order");
            note.setText("");
            lines.setItems(FXCollections.observableArrayList());
            setActions(null);
            return;
        }
        OrderDetail detail = services.purchasing().detail(summary.id());
        header.setText("PO " + summary.id() + " · " + summary.supplierName() + " · " + label(summary.status()));
        note.setText(summary.note() == null ? "" : summary.note());
        lines.setItems(FXCollections.observableArrayList(detail.lines()));
        setActions(summary.status());
    }

    private void setActions(PurchaseStatus status) {
        boolean draft = status == PurchaseStatus.DRAFT;
        boolean open = status == PurchaseStatus.ORDERED || status == PurchaseStatus.PARTIAL;
        addItem.setDisable(!draft);
        removeItem.setDisable(!draft);
        send.setDisable(!draft);
        receive.setDisable(!open);
        cancel.setDisable(!(draft || status == PurchaseStatus.ORDERED));
        export.setDisable(status == null);
    }

    private void selectOrder(long id) {
        for (OrderSummary order : orders.getItems()) {
            if (order.id() == id) {
                orders.getSelectionModel().select(order);
                return;
            }
        }
    }

    /** Null means every status. */
    private PurchaseStatus statusFilterValue() {
        int index = statusFilter.getSelectionModel().getSelectedIndex();
        return index <= 0 ? null : PurchaseStatus.values()[index - 1];
    }

    private OrderSummary selectedOrder() {
        OrderSummary selected = orders.getSelectionModel().getSelectedItem();
        if (selected == null) {
            throw new BusinessException("Select an order first");
        }
        return selected;
    }

    private void createOrder() {
        ComboBox<Supplier> supplier = new ComboBox<>(FXCollections.observableArrayList(services.inventory().listSuppliers()));
        TextField noteField = new TextField();
        noteField.setPromptText("Optional, e.g. monthly restock");
        var grid = Ui.grid();
        grid.addRow(0, new Label("Supplier"), supplier);
        grid.addRow(1, new Label("Note"), noteField);
        boolean created = FormDialog.run(null, "New purchase order", "Start a draft order", grid, "Create", () -> {
            Supplier chosen = supplier.getValue();
            if (chosen == null) {
                throw new BusinessException("Choose a supplier for this order");
            }
            services.purchasing().createDraft(chosen.id(), noteField.getText());
        });
        if (created) {
            statusFilter.getSelectionModel().selectFirst();
            loadOrders();
            Toast.show("Draft order created");
        }
    }

    private void addLine() {
        OrderSummary order = selectedOrder();
        ComboBox<Product> product = new ComboBox<>(FXCollections.observableArrayList(
                services.inventory().listProducts().stream().filter(p -> p.active() && !p.isGiftCard()).toList()));
        product.setConverter(new StringConverter<>() {
            @Override
            public String toString(Product p) {
                return p == null ? "" : p.sku() + " · " + p.name();
            }

            @Override
            public Product fromString(String text) {
                return null;
            }
        });
        TextField qty = new TextField("1");
        TextField cost = new TextField();
        product.valueProperty().addListener((obs, was, now) -> {
            if (now != null) {
                cost.setText(MoneyFormat.formatForInput(now.costCents()));
            }
        });
        var grid = Ui.grid();
        grid.addRow(0, new Label("Product"), product);
        grid.addRow(1, new Label("Quantity"), qty);
        grid.addRow(2, new Label("Unit cost"), cost);
        boolean added = FormDialog.run(null, "Add item", "Add an item to PO " + order.id(), grid, "Add", () -> {
            Product chosen = product.getValue();
            if (chosen == null) {
                throw new BusinessException("Choose a product");
            }
            services.purchasing().addLine(order.id(), chosen.id(), Ui.parseWhole(qty.getText(), "Quantity"),
                    Ui.parseMoney(cost.getText(), "Unit cost"));
        });
        if (added) {
            loadOrders();
            selectOrder(order.id());
        }
    }

    private void removeLine() {
        OrderSummary order = selectedOrder();
        OrderLine line = lines.getSelectionModel().getSelectedItem();
        if (line == null) {
            throw new BusinessException("Select an item on the order first");
        }
        if (!Ui.confirm("Remove " + line.name() + " from PO " + order.id() + "?")) {
            return;
        }
        services.purchasing().removeLine(line.id());
        loadOrders();
        selectOrder(order.id());
    }

    private void sendOrder() {
        OrderSummary order = selectedOrder();
        if (!Ui.confirm("Mark PO " + order.id() + " as sent to " + order.supplierName()
                + "? Its items cannot be changed after this.")) {
            return;
        }
        services.purchasing().order(order.id());
        loadOrders();
        selectOrder(order.id());
        Toast.show("PO " + order.id() + " sent");
    }

    /** Books in a delivery. Each line can arrive in part, and a cost can be corrected if the invoice differs. */
    private void receiveDelivery() {
        OrderSummary order = selectedOrder();
        List<OrderLine> open = services.purchasing().detail(order.id()).lines().stream()
                .filter(l -> l.qtyRemaining() > 0)
                .toList();
        if (open.isEmpty()) {
            throw new BusinessException("Everything on this order has already arrived");
        }
        var grid = Ui.grid();
        grid.addRow(0, new Label("Item"), new Label("To come"), new Label("Arrived now"), new Label("Unit cost"));
        List<TextField> counts = new ArrayList<>();
        List<TextField> costs = new ArrayList<>();
        int row = 1;
        for (OrderLine line : open) {
            TextField count = new TextField(String.valueOf(line.qtyRemaining()));
            TextField cost = new TextField(MoneyFormat.formatForInput(line.unitCostCents()));
            counts.add(count);
            costs.add(cost);
            grid.addRow(row++, new Label(line.sku() + " " + line.name()),
                    new Label(String.valueOf(line.qtyRemaining())), count, cost);
        }
        TextField invoice = new TextField();
        invoice.setPromptText("Optional supplier invoice number");
        grid.add(new Label("Invoice"), 0, row);
        grid.add(invoice, 1, row, 3, 1);

        boolean received = FormDialog.run(null, "Receive delivery", "Book in what arrived on PO " + order.id(), grid,
                "Receive", () -> {
                    List<ReceiveLine> arrived = new ArrayList<>();
                    for (int i = 0; i < open.size(); i++) {
                        int qty = Ui.parseWhole(counts.get(i).getText(), "Arrived now");
                        long unitCost = Ui.parseMoney(costs.get(i).getText(), "Unit cost");
                        arrived.add(new ReceiveLine(open.get(i).id(), qty, unitCost));
                    }
                    services.purchasing().receive(order.id(), invoice.getText(), arrived);
                });
        if (received) {
            loadOrders();
            selectOrder(order.id());
            Toast.show("Delivery booked in");
        }
    }

    private void cancelOrder() {
        OrderSummary order = selectedOrder();
        if (!Ui.confirm("Cancel PO " + order.id() + "? This cannot be undone.")) {
            return;
        }
        services.purchasing().cancel(order.id());
        loadOrders();
        selectOrder(order.id());
    }

    private void exportOrder() {
        OrderSummary order = selectedOrder();
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export purchase order");
        chooser.setInitialFileName("PO-" + order.id() + ".csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        File file = chooser.showSaveDialog(getScene() == null ? null : getScene().getWindow());
        if (file != null) {
            services.purchasing().exportCsv(order.id(), file.toPath());
            Ui.showInfo("Purchase order exported to " + file.getAbsolutePath());
        }
    }

    /** Starts one draft per supplier from the suggestions, then shows the drafts. */
    private void draftFromSuggestions() {
        if (suggestions.getItems().isEmpty()) {
            throw new BusinessException("Nothing is low on stock");
        }
        List<Long> drafts = services.purchasing().draftFromSuggestions();
        if (drafts.isEmpty()) {
            throw new BusinessException("Nothing new to draft: every low-stock product is already on an open order, "
                    + "or has no supplier");
        }
        Toast.show(drafts.size() + " draft order(s) created");
        statusFilter.getSelectionModel().selectFirst();
        loadOrders();
        selectOrder(drafts.get(0));
        tabs.getSelectionModel().select(ordersTab);
    }
}
