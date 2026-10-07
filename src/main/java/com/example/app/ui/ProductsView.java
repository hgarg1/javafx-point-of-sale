package com.example.app.ui;

import com.example.app.domain.Category;
import com.example.app.domain.Money;
import com.example.app.domain.Product;
import com.example.app.domain.ProductDraft;
import com.example.app.domain.Supplier;
import com.example.app.service.AppServices;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Product catalogue: list in the centre, edit form on the right. Stock is changed on the Stock screen. */
final class ProductsView extends BorderPane {

    private final AppServices services;
    private final TableView<Product> table = Ui.table();
    private final Map<Long, String> categoryNames = new HashMap<>();

    private final TextField sku = new TextField();
    private final TextField barcode = new TextField();
    private final TextField name = new TextField();
    private final ComboBox<Category> category = new ComboBox<>();
    private final ComboBox<Supplier> supplier = new ComboBox<>();
    private final TextField price = new TextField();
    private final TextField cost = new TextField();
    private final TextField taxPercent = new TextField();
    private final TextField reorderLevel = new TextField();
    private final TextField initialStock = new TextField();
    private final CheckBox active = new CheckBox("Active (available for sale)");
    private final Label formTitle = Ui.heading("New product");
    private final Label message = Ui.muted("");

    /** The product being edited, or null when creating a new one. */
    private Product selected;

    ProductsView(AppServices services) {
        this.services = services;

        table.getColumns().addAll(
                Ui.<Product>column("SKU", 90, Product::sku),
                Ui.<Product>column("Name", 200, Product::name),
                Ui.<Product>column("Category", 110, p -> categoryNames.getOrDefault(p.categoryId(), "")),
                Ui.<Product>column("Price", 80, p -> Ui.money(p.priceCents())),
                Ui.<Product>column("On hand", 70, p -> String.valueOf(p.stockQty())),
                Ui.<Product>column("Active", 60, p -> p.active() ? "Yes" : "No"));
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> fill(now));
        VBox.setVgrow(table, Priority.ALWAYS);

        category.setConverter(noneFirstConverter(Category::name));
        supplier.setConverter(noneFirstConverter(Supplier::name));
        barcode.setPromptText("Optional");
        taxPercent.setPromptText("e.g. 8.25");
        taxPercent.setTooltip(new Tooltip("Sales tax as a percentage"));
        initialStock.setTooltip(new Tooltip("Quantity on hand when the product is created. Change it later on the Stock screen."));

        var grid = Ui.grid();
        int row = 0;
        grid.addRow(row++, new Label("SKU"), sku);
        grid.addRow(row++, new Label("Barcode"), barcode);
        grid.addRow(row++, new Label("Name"), name);
        grid.addRow(row++, new Label("Category"), withAddButton(category, this::addCategory));
        grid.addRow(row++, new Label("Supplier"), withAddButton(supplier, this::addSupplier));
        grid.addRow(row++, new Label("Price"), price);
        grid.addRow(row++, new Label("Cost"), cost);
        grid.addRow(row++, new Label("Tax rate %"), taxPercent);
        grid.addRow(row++, new Label("Reorder at"), reorderLevel);
        grid.addRow(row++, new Label("Initial stock"), initialStock);
        grid.add(active, 1, row);

        Button save = new Button("Save");
        save.setDefaultButton(true);
        save.setOnAction(e -> Ui.attempt(this::save));
        Button newProduct = new Button("New product");
        newProduct.setOnAction(e -> table.getSelectionModel().clearSelection());

        VBox form = new VBox(10, formTitle, grid, new HBox(8, save, newProduct), message);
        form.setPadding(new Insets(0, 0, 0, 16));
        form.setMinWidth(340);

        setCenter(table);
        setRight(form);

        reload();
        clearForm();
    }

    private void reload() {
        categoryNames.clear();
        var categories = services.inventory().listCategories();
        categories.forEach(c -> categoryNames.put(c.id(), c.name()));
        category.getItems().setAll(withNoneFirst(categories));

        var suppliers = services.inventory().listSuppliers();
        supplier.getItems().setAll(withNoneFirst(suppliers));

        table.setItems(FXCollections.observableArrayList(services.inventory().listProducts()));
    }

    private void fill(Product product) {
        if (product == null) {
            clearForm();
            return;
        }
        selected = product;
        formTitle.setText("Edit " + product.name());
        sku.setText(product.sku());
        barcode.setText(product.barcode() == null ? "" : product.barcode());
        name.setText(product.name());
        category.setValue(findIn(category.getItems(), c -> Objects.equals(c.id(), product.categoryId())));
        supplier.setValue(findIn(supplier.getItems(), s -> Objects.equals(s.id(), product.supplierId())));
        price.setText(Money.toPlainAmount(product.priceCents()));
        cost.setText(Money.toPlainAmount(product.costCents()));
        taxPercent.setText(Ui.plainPercent(product.taxRateBp()));
        reorderLevel.setText(String.valueOf(product.reorderLevel()));
        initialStock.setText("");
        initialStock.setDisable(true);
        initialStock.setPromptText("Use the Stock screen");
        active.setSelected(product.active());
        message.setText("");
    }

    private void clearForm() {
        selected = null;
        formTitle.setText("New product");
        sku.clear();
        barcode.clear();
        name.clear();
        category.setValue(null);
        supplier.setValue(null);
        price.clear();
        cost.setText("0.00");
        taxPercent.clear();
        reorderLevel.setText("0");
        initialStock.setText("0");
        initialStock.setDisable(false);
        initialStock.setPromptText("");
        active.setSelected(true);
        message.setText("");
    }

    private void save() {
        ProductDraft draft = new ProductDraft(
                sku.getText().trim(),
                barcode.getText(),
                name.getText(),
                category.getValue() == null ? null : category.getValue().id(),
                supplier.getValue() == null ? null : supplier.getValue().id(),
                Ui.parseMoney(price.getText(), "Price"),
                Ui.parseMoney(cost.getText(), "Cost"),
                Ui.parsePercentToBp(taxPercent.getText(), "Tax rate"),
                Ui.parseWhole(reorderLevel.getText(), "Reorder level"),
                active.isSelected());
        if (selected == null) {
            services.inventory().createProduct(draft, Ui.parseWhole(initialStock.getText(), "Initial stock"));
            message.setText("Saved " + draft.name() + ".");
        } else {
            services.inventory().updateProduct(selected.id(), draft);
            message.setText("Updated " + draft.name() + ".");
        }
        table.getSelectionModel().clearSelection();
        reload();
        clearForm();
    }

    private void addCategory() {
        Ui.attempt(() -> {
            promptName("New category").ifPresent(n -> services.inventory().addCategory(n));
            reload();
        });
    }

    private void addSupplier() {
        Ui.attempt(() -> {
            promptName("New supplier").ifPresent(n -> services.inventory().addSupplier(n, null));
            reload();
        });
    }

    private static HBox withAddButton(ComboBox<?> box, Runnable onAdd) {
        Button add = new Button("+");
        add.setTooltip(new Tooltip("Add new"));
        add.setOnAction(e -> onAdd.run());
        return new HBox(6, box, add);
    }

    private static Optional<String> promptName(String title) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText("Name:");
        return dialog.showAndWait().map(String::trim).filter(s -> !s.isEmpty());
    }

    /** Combo box items with a leading null entry, shown as "(none)". */
    private static <T> java.util.List<T> withNoneFirst(java.util.List<T> items) {
        var list = new java.util.ArrayList<T>();
        list.add(null);
        list.addAll(items);
        return list;
    }

    private static <T> T findIn(Iterable<T> items, java.util.function.Predicate<T> match) {
        for (T item : items) {
            if (item != null && match.test(item)) {
                return item;
            }
        }
        return null;
    }

    private static <T> StringConverter<T> noneFirstConverter(Function<T, String> label) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "(none)" : label.apply(value);
            }

            @Override
            public T fromString(String text) {
                return null;
            }
        };
    }
}
