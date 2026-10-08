package com.example.app.ui;

import com.example.app.domain.Category;
import com.example.app.domain.Money;
import com.example.app.domain.Product;
import com.example.app.domain.ProductDraft;
import com.example.app.domain.ProductKind;
import com.example.app.domain.Supplier;
import com.example.app.error.PosException;
import com.example.app.service.AppServices;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Product catalogue: list in the centre, edit form on the right. Stock is changed on the Stock screen.
 * Reloads keep the product being edited selected; only the drop-down lists refresh when a category or supplier is
 * added.
 */
final class ProductsView extends BorderPane implements Screen {

    private static final Logger LOG = Logger.getLogger(ProductsView.class.getName());

    private static final String ALL = "All products";
    private static final String FOR_SALE = "For sale";
    private static final String NOT_FOR_SALE = "Not for sale";
    private static final String LOW_STOCK = "Low stock";

    private final AppServices services;
    private final TableView<Product> table = Ui.table();
    private final ObservableList<Product> allProducts = FXCollections.observableArrayList();
    private final FilteredList<Product> shown = new FilteredList<>(allProducts, p -> true);
    private final TextField filter = new TextField();
    private final ChoiceBox<String> status = new ChoiceBox<>(
            FXCollections.observableArrayList(ALL, FOR_SALE, NOT_FOR_SALE, LOW_STOCK));
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
    private final TextField reorderQty = new TextField();
    private final TextField initialStock = new TextField();
    private final CheckBox active = new CheckBox("Active (available for sale)");
    private final CheckBox giftCard = new CheckBox("Gift card (sold at face value, not stocked)");
    private final Label formTitle = Ui.heading("New product");
    private final Label message = Ui.error("");

    /** The product being edited, or null when creating a new one. */
    private Product selected;

    ProductsView(AppServices services) {
        this.services = services;

        table.getColumns().addAll(
                Columns.text("SKU", 90, Product::sku),
                Columns.text("Name", 200, Product::name),
                Columns.text("Category", 110, p -> categoryNames.getOrDefault(p.categoryId(), "")),
                Columns.money("Price", 80, Product::priceCents),
                Columns.whole("On hand", 70, Product::stockQty),
                Columns.text("Active", 60, p -> p.active() ? "Yes" : "No"));
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> fill(now));
        table.setItems(shown);
        VBox.setVgrow(table, Priority.ALWAYS);

        filter.setPromptText("Search name or SKU");
        filter.textProperty().addListener((obs, was, now) -> applyFilter());
        status.setValue(ALL);
        status.valueProperty().addListener((obs, was, now) -> applyFilter());
        HBox filters = new HBox(8, filter, status);
        HBox.setHgrow(filter, Priority.ALWAYS);
        VBox listPane = new VBox(8, filters, table);
        VBox.setVgrow(table, Priority.ALWAYS);

        category.setConverter(noneFirstConverter(Category::name));
        supplier.setConverter(noneFirstConverter(Supplier::name));
        barcode.setPromptText("Optional");
        taxPercent.setPromptText("e.g. 8.25");
        taxPercent.setTooltip(new Tooltip("Sales tax as a percentage"));
        initialStock.setTooltip(new Tooltip("Quantity on hand when the product is created. Change it later on the Stock screen."));
        reorderQty.setTooltip(new Tooltip("How many to order when this product runs low. Zero means up to twice the reorder level."));

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
        grid.addRow(row++, new Label("Order quantity"), reorderQty);
        grid.addRow(row++, new Label("Initial stock"), initialStock);
        grid.add(active, 1, row);
        grid.add(giftCard, 1, row + 1);

        Button save = new Button("Save");
        save.setDefaultButton(true);
        save.setOnAction(e -> saveFromForm());
        Button newProduct = new Button("New product");
        newProduct.setOnAction(e -> table.getSelectionModel().clearSelection());

        VBox form = new VBox(10, formTitle, grid, new HBox(8, save, newProduct), message);
        form.setPadding(new Insets(0, 0, 0, 16));
        form.setMinWidth(340);

        setCenter(listPane);
        setRight(form);
        setPadding(new Insets(0, 0, 0, 0));

        onShow();
    }

    @Override
    public void onShow() {
        reloadLists();
        reloadProducts();
        if (selected == null) {
            clearForm();
        }
    }

    /** Category and supplier drop-downs. Adding one refreshes only these, so the form and selection are untouched. */
    private void reloadLists() {
        categoryNames.clear();
        List<Category> categories = services.inventory().listCategories();
        categories.forEach(c -> categoryNames.put(c.id(), c.name()));
        Category currentCategory = category.getValue();
        category.getItems().setAll(withNoneFirst(categories));
        category.setValue(findIn(category.getItems(), c -> currentCategory != null && c.id() == currentCategory.id()));

        Supplier currentSupplier = supplier.getValue();
        List<Supplier> suppliers = services.inventory().listSuppliers();
        supplier.getItems().setAll(withNoneFirst(suppliers));
        supplier.setValue(findIn(supplier.getItems(), s -> currentSupplier != null && s.id() == currentSupplier.id()));
    }

    /** The product table, keeping the product being edited selected. The filters narrow what is shown, not what is kept. */
    private void reloadProducts() {
        long keep = selected == null ? -1 : selected.id();
        allProducts.setAll(services.inventory().listProducts());
        applyFilter();
        for (int i = 0; i < table.getItems().size(); i++) {
            if (table.getItems().get(i).id() == keep) {
                table.getSelectionModel().select(i);
                break;
            }
        }
    }

    /** Shows the products matching the search text and the status choice. */
    private void applyFilter() {
        String text = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(Locale.ROOT);
        String choice = status.getValue() == null ? ALL : status.getValue();
        shown.setPredicate(p -> (text.isEmpty()
                || p.name().toLowerCase(Locale.ROOT).contains(text)
                || p.sku().toLowerCase(Locale.ROOT).contains(text))
                && matchesStatus(p, choice));
    }

    private static boolean matchesStatus(Product p, String choice) {
        return switch (choice) {
            case FOR_SALE -> p.active();
            case NOT_FOR_SALE -> !p.active();
            case LOW_STOCK -> p.isLowStock();
            default -> true;
        };
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
        reorderQty.setText(String.valueOf(product.reorderQty()));
        initialStock.setText("");
        initialStock.setDisable(true);
        initialStock.setPromptText("Use the Stock screen");
        active.setSelected(product.active());
        giftCard.setSelected(product.isGiftCard());
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
        reorderQty.setText("0");
        initialStock.setText("0");
        initialStock.setDisable(false);
        initialStock.setPromptText("");
        active.setSelected(true);
        giftCard.setSelected(false);
        message.setText("");
    }

    /** Saves the form. A problem is shown above the buttons and everything typed stays where it is. */
    private void saveFromForm() {
        try {
            save();
        } catch (PosException e) {
            message.setText(e.getMessage());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Saving a product failed", e);
            message.setText("Something went wrong. The details were written to the log.");
        }
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
                Ui.parseWhole(reorderQty.getText(), "Order quantity"),
                active.isSelected(),
                giftCard.isSelected() ? ProductKind.GIFT_CARD : ProductKind.STOCK);
        String saved;
        if (selected == null) {
            services.inventory().createProduct(draft, Ui.parseWhole(initialStock.getText(), "Initial stock"));
            saved = "Saved " + draft.name() + ".";
        } else {
            services.inventory().updateProduct(selected.id(), draft);
            saved = "Updated " + draft.name() + ".";
        }
        table.getSelectionModel().clearSelection();
        reloadProducts();
        clearForm();
        message.setText("");
        Toast.show(saved);
    }

    private void addCategory() {
        TextField name = new TextField();
        name.setPromptText("Category name");
        boolean added = FormDialog.run(null, "New category", "Name the new category", name, "Add", () ->
                services.inventory().addCategory(name.getText()));
        if (added) {
            reloadLists();
        }
    }

    private void addSupplier() {
        TextField name = new TextField();
        name.setPromptText("Supplier name");
        boolean added = FormDialog.run(null, "New supplier", "Name the new supplier", name, "Add", () ->
                services.inventory().addSupplier(name.getText(), null));
        if (added) {
            reloadLists();
        }
    }

    private HBox withAddButton(ComboBox<?> box, Runnable onAdd) {
        Button add = new Button("+");
        add.setTooltip(new Tooltip("Add new"));
        add.setOnAction(e -> Ui.attempt(onAdd));
        return new HBox(6, box, add);
    }

    /** Combo box items with a leading null entry, shown as "(none)". */
    private static <T> List<T> withNoneFirst(List<T> items) {
        List<T> list = new ArrayList<>();
        list.add(null);
        list.addAll(items);
        return list;
    }

    private static <T> T findIn(Iterable<T> items, Predicate<T> match) {
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
