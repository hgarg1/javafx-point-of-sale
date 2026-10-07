package com.example.app.ui;

import com.example.app.domain.Product;
import com.example.app.domain.User;
import com.example.app.error.AccessDeniedException;
import com.example.app.error.ApprovalRequiredException;
import com.example.app.error.BusinessException;
import com.example.app.service.AppServices;
import com.example.app.service.CheckoutService.CartItem;
import com.example.app.service.CheckoutService.PaymentRequest;
import com.example.app.service.CheckoutService.Quote;
import com.example.app.service.CheckoutService.SaleDetails;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Optional;

/** Scan or search products, build the cart, then take payment. */
final class CheckoutView extends BorderPane {

    /** A cart line. Mutable so quantity and discount can be edited in place. */
    static final class CartLine {
        final Product product;
        int qty;
        int discountBp;

        CartLine(Product product, int qty) {
            this.product = product;
            this.qty = qty;
        }
    }

    private final AppServices services;
    private final ObservableList<CartLine> cart = FXCollections.observableArrayList();
    private final TableView<CartLine> cartTable = Ui.table();
    private final TableView<Product> results = Ui.table();
    private final TextField scan = new TextField();
    private final Label subtotal = new Label();
    private final Label discount = new Label();
    private final Label tax = new Label();
    private final Label total = new Label();
    private final Label status = Ui.muted("");
    private final Button pay = new Button("Pay (F9)");

    CheckoutView(AppServices services) {
        this.services = services;

        scan.setPromptText("Scan barcode or type SKU / name, then Enter");
        scan.setOnAction(e -> Ui.attempt(this::scanOrSearch));
        // Key presses bubble up from the focused control, so F9 works from anywhere on this screen.
        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.F9) {
                Ui.attempt(this::pay);
            }
        });

        results.getColumns().addAll(
                Ui.<Product>column("SKU", 90, Product::sku),
                Ui.<Product>column("Name", 220, Product::name),
                Ui.<Product>column("Price", 80, p -> Ui.money(p.priceCents())),
                Ui.<Product>column("In stock", 70, p -> String.valueOf(p.stockQty())));
        results.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                addSelectedResult();
            }
        });
        Button addSelected = new Button("Add to cart");
        addSelected.setOnAction(e -> addSelectedResult());
        VBox left = new VBox(8, Ui.heading("Products"), scan, results, addSelected);
        VBox.setVgrow(results, Priority.ALWAYS);
        left.setPrefWidth(440);

        cartTable.setItems(cart);
        cartTable.setPlaceholder(new Label("Cart is empty"));
        cartTable.getColumns().addAll(
                Ui.<CartLine>column("Item", 220, l -> l.product.name()),
                Ui.<CartLine>column("Qty", 50, l -> String.valueOf(l.qty)),
                Ui.<CartLine>column("Unit", 80, l -> Ui.money(l.product.priceCents())),
                Ui.<CartLine>column("Discount", 80, l -> l.discountBp == 0 ? "" : Ui.percent(l.discountBp)));

        Button editQty = new Button("Quantity...");
        editQty.setOnAction(e -> edit(this::changeQuantity));
        Button editDiscount = new Button("Discount %...");
        editDiscount.setOnAction(e -> edit(this::changeDiscount));
        Button remove = new Button("Remove");
        remove.setOnAction(e -> edit(() -> cart.remove(selectedLine())));
        Button clear = new Button("Clear cart");
        clear.setOnAction(e -> edit(cart::clear));
        HBox lineButtons = new HBox(8, editQty, editDiscount, remove, clear);

        pay.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        pay.setMaxWidth(Double.MAX_VALUE);
        pay.setOnAction(e -> Ui.attempt(this::pay));
        VBox totals = new VBox(6,
                Ui.heading("Totals"),
                row("Subtotal", subtotal), row("Discount", discount), row("Tax", tax), row("Total", total),
                pay, status);
        totals.setPadding(new Insets(12));
        totals.setStyle("-fx-background-color: #fafafa; -fx-border-color: #e0e0e0;");

        VBox right = new VBox(8, Ui.heading("Cart"), cartTable, lineButtons, totals);
        VBox.setVgrow(cartTable, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);

        setCenter(new HBox(16, left, right));
        setPadding(new Insets(12));

        boolean shiftOpen = services.shifts().currentShift().isPresent();
        pay.setDisable(!shiftOpen);
        if (!shiftOpen) {
            status.setText("Open a shift on the Shift screen before selling.");
        }
        refreshTotals();
    }

    private void scanOrSearch() {
        String text = scan.getText();
        scan.clear();
        if (text == null || text.isBlank()) {
            return;
        }
        Optional<Product> byCode = services.inventory().findByCode(text);
        if (byCode.isPresent()) {
            results.getItems().clear();
            addToCart(byCode.get());
            return;
        }
        List<Product> matches = services.inventory().search(text);
        results.getItems().setAll(matches);
        if (matches.size() == 1) {
            results.getItems().clear();
            addToCart(matches.get(0));
        } else if (matches.isEmpty()) {
            status.setText("No product matches \"" + text + "\"");
        }
    }

    private void addSelectedResult() {
        Product selected = results.getSelectionModel().getSelectedItem();
        if (selected != null) {
            Ui.attempt(() -> addToCart(selected));
        }
    }

    private void addToCart(Product product) {
        if (!product.active()) {
            throw new BusinessException(product.name() + " is not for sale");
        }
        Optional<CartLine> existing = cart.stream()
                .filter(l -> l.product.id() == product.id() && l.discountBp == 0)
                .findFirst();
        if (existing.isPresent()) {
            existing.get().qty++;
        } else {
            cart.add(new CartLine(product, 1));
        }
        status.setText("");
        cartTable.refresh();
        refreshTotals();
    }

    private CartLine selectedLine() {
        CartLine line = cartTable.getSelectionModel().getSelectedItem();
        if (line == null) {
            throw new BusinessException("Select a line in the cart first");
        }
        return line;
    }

    private void changeQuantity() {
        CartLine line = selectedLine();
        textPrompt("Quantity", "Quantity for " + line.product.name(), String.valueOf(line.qty)).ifPresent(text -> {
            int qty = Ui.parseWhole(text, "Quantity");
            if (qty <= 0) {
                throw new BusinessException("Quantity must be at least 1");
            }
            line.qty = qty;
        });
    }

    private void changeDiscount() {
        CartLine line = selectedLine();
        textPrompt("Discount %", "Discount for " + line.product.name(), Ui.plainPercent(line.discountBp))
                .ifPresent(text -> {
                    int bp = Ui.parsePercentToBp(text, "Discount");
                    if (bp < 0 || bp > 10_000) {
                        throw new BusinessException("Discount must be between 0% and 100%");
                    }
                    line.discountBp = bp;
                });
    }

    /** Runs a cart edit, then refreshes the table and totals. Errors are shown to the user. */
    private void edit(Runnable change) {
        Ui.attempt(change);
        cartTable.refresh();
        refreshTotals();
    }

    private void refreshTotals() {
        if (cart.isEmpty()) {
            setTotals(new Quote(0, 0, 0, 0));
            return;
        }
        try {
            setTotals(services.checkout().quote(toItems()));
        } catch (BusinessException | AccessDeniedException e) {
            status.setText(e.getMessage());
        }
    }

    private void setTotals(Quote q) {
        subtotal.setText(Ui.money(q.subtotalCents()));
        discount.setText(q.discountCents() == 0 ? Ui.money(0) : "-" + Ui.money(q.discountCents()));
        tax.setText(Ui.money(q.taxCents()));
        total.setText(Ui.money(q.totalCents()));
    }

    private List<CartItem> toItems() {
        return cart.stream().map(l -> new CartItem(l.product.id(), l.qty, l.discountBp)).toList();
    }

    private void pay() {
        if (cart.isEmpty()) {
            throw new BusinessException("The cart is empty");
        }
        List<CartItem> items = toItems();
        long totalCents = services.checkout().quote(items).totalCents();
        Optional<List<PaymentRequest>> tenders = PaymentDialog.ask(totalCents);
        if (tenders.isEmpty()) {
            return;
        }

        Long approverId = null;
        SaleDetails details;
        while (true) {
            try {
                details = services.checkout().checkout(items, tenders.get(), approverId);
                break;
            } catch (ApprovalRequiredException e) {
                Optional<User> approver = ApproverDialog.ask(services, e.getMessage());
                if (approver.isEmpty()) {
                    return;
                }
                approverId = approver.get().id();
            }
        }

        String cashier = services.session().requireUser().username();
        ReceiptDialog.show(ReceiptFormatter.format(services.storeName(), details, cashier));
        cart.clear();
        cartTable.refresh();
        refreshTotals();
        status.setText("Sale " + details.sale().receiptNo() + " completed.");
    }

    private static Optional<String> textPrompt(String title, String header, String value) {
        TextInputDialog dialog = new TextInputDialog(value);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText(title + ":");
        return dialog.showAndWait();
    }

    private static HBox row(String label, Label value) {
        Label name = new Label(label);
        name.setMinWidth(90);
        value.setMinWidth(110);
        value.setStyle("-fx-font-weight: bold;");
        return new HBox(8, name, value);
    }
}
