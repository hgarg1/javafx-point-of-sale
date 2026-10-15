package com.example.app.ui;

import com.example.app.domain.Product;
import com.example.app.domain.Shift;
import com.example.app.domain.User;
import com.example.app.error.ApprovalRequiredException;
import com.example.app.error.BusinessException;
import com.example.app.service.AppServices;
import com.example.app.service.CheckoutService;
import com.example.app.service.CheckoutService.CartItem;
import com.example.app.service.CheckoutService.LineQuote;
import com.example.app.service.CheckoutService.PaymentRequest;
import com.example.app.service.CheckoutService.Quote;
import com.example.app.service.CheckoutService.SaleDetails;
import com.example.app.service.HoldService.HeldCart;
import com.example.app.service.HoldService.Recalled;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.layout.FlowPane;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Scan or search products, build the cart, take payment. Cached by the shell, so the cart survives moving to another
 * screen; {@link #onShow()} refreshes prices, stock and the shift state each time it comes back.
 */
final class CheckoutView extends BorderPane implements Screen {

    /** A line in the cart. Quantity and discount are edited in place; prices come from the quote. */
    static final class CartLine {
        final Product product;
        int qty;
        int discountBp;

        CartLine(Product product, int qty) {
            this.product = product;
            this.qty = qty;
        }
    }

    /** One table row: a cart line with the amounts the quote worked out for it. */
    private record Row(CartLine line, LineQuote quote) {
    }

    private static final Quote EMPTY_QUOTE = new Quote(List.of(), 0, 0, 0, 0);

    private final AppServices services;
    /** Told whenever the number of held sales changes, so the header can show it. */
    private final Runnable onHeldChanged;
    private final List<CartLine> cart = new ArrayList<>();
    private final TableView<Row> cartTable = Ui.table();
    private final TableView<Product> results = Ui.table();
    private final TextField scan = new TextField();
    private final Label subtotal = new Label();
    private final Label discount = new Label();
    private final Label tax = new Label();
    private final Label total = new Label();
    private final Label status = Ui.muted("");
    private final Label shiftNotice = Ui.muted("");
    private final Label availability = Ui.error("");
    private final Label searchSummary = Ui.muted("");
    private final Label receiptReminder = Ui.muted("");
    private final Button lastReceipt = new Button("View last receipt");
    private String lastReceiptText;
    private final Button pay = new Button("Pay (F9)");
    private final Button hold = new Button("Hold (F6)");
    private final Button recall = new Button("Recall (F7)");

    private Quote quote = EMPTY_QUOTE;
    /** A manager who approved a large discount on this cart. Kept until the cart is cleared. */
    private Long approverId;
    /** Identifies one payment attempt, so a retry after an error can never charge twice. Reset when the cart changes. */
    private String paymentKey;

    CheckoutView(AppServices services, Runnable onHeldChanged) {
        this.services = services;
        this.onHeldChanged = onHeldChanged;

        scan.setPromptText("Scan a barcode, or type a SKU or name, then Enter");
        scan.setOnAction(e -> Ui.attempt(this::scanOrSearch));
        scan.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DOWN && !results.getItems().isEmpty()) {
                results.getSelectionModel().selectFirst();
                results.requestFocus();
                e.consume();
            }
        });
        results.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                addSelectedResult();
                e.consume();
            }
        });
        // Key presses reach this node from whatever control has focus, so F9 works from anywhere on the screen.
        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                scan.clear();
                status.setText("");
                showBrowseList();
                scan.requestFocus();
                e.consume();
            } else if (e.getCode() == KeyCode.F9) {
                Ui.attempt(this::pay);
            } else if (e.getCode() == KeyCode.F2) {
                scan.requestFocus();
            } else if (e.getCode() == KeyCode.F6) {
                Ui.attempt(this::holdSale);
            } else if (e.getCode() == KeyCode.F7) {
                Ui.attempt(this::recallSale);
            }
        });

        results.getColumns().addAll(
                Columns.text("SKU", 125, Product::sku),
                Columns.text("Name", 220, Product::name),
                Columns.money("Price", 80, Product::priceCents),
                Columns.whole("In stock", 70, Product::stockQty));
        results.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                addSelectedResult();
            }
        });
        Button addSelected = new Button("Add to cart");
        addSelected.disableProperty().bind(results.getSelectionModel().selectedItemProperty().isNull());
        addSelected.setOnAction(e -> addSelectedResult());
        VBox left = new VBox(8, Ui.heading("Products"), scan, searchSummary, results, addSelected);
        VBox.setVgrow(results, Priority.ALWAYS);
        left.setMinWidth(260);
        left.setPadding(new Insets(0, 12, 0, 0));

        cartTable.setPlaceholder(Ui.muted("Scan an item or select a product to start a sale."));
        cartTable.setMinHeight(120);
        total.getStyleClass().add("total-amount");
        cartTable.getColumns().addAll(
                Columns.text("Item", 200, r -> r.line().product.name()),
                Columns.whole("Qty", 50, r -> r.line().qty),
                Columns.money("Unit", 80, r -> r.quote().unitPriceCents()),
                Columns.text("Discount", 80, r -> r.line().discountBp == 0 ? "" : Ui.percent(r.line().discountBp)),
                Columns.money("Line total", 100, r -> r.quote().totalCents()));

        Button editQty = new Button("Quantity...");
        editQty.setOnAction(e -> Ui.attempt(this::changeQuantity));
        Button editDiscount = new Button("Discount %...");
        editDiscount.setOnAction(e -> Ui.attempt(this::changeDiscount));
        Button remove = new Button("Remove");
        remove.getStyleClass().add("danger-quiet");
        remove.setOnAction(e -> Ui.attempt(this::removeSelected));
        Button clear = new Button("Clear cart");
        clear.getStyleClass().add("danger-quiet");
        clear.setOnAction(e -> Ui.attempt(this::clearCart));
        Button less = new Button("−");
        less.setAccessibleText("Decrease selected quantity");
        less.setOnAction(e -> Ui.attempt(() -> adjustQuantity(-1)));
        Button more = new Button("+");
        more.setAccessibleText("Increase selected quantity");
        more.setOnAction(e -> Ui.attempt(() -> adjustQuantity(1)));
        for (Button action : List.of(editQty, editDiscount, remove, less, more)) {
            action.disableProperty().bind(cartTable.getSelectionModel().selectedItemProperty().isNull());
        }
        clear.disableProperty().bind(javafx.beans.binding.Bindings.isEmpty(cartTable.getItems()));
        FlowPane lineButtons = new FlowPane(8, 8, less, more, editQty, editDiscount, remove, clear);

        hold.setOnAction(e -> Ui.attempt(this::holdSale));
        recall.setOnAction(e -> Ui.attempt(this::recallSale));
        HBox parking = new HBox(8, hold, recall);

        pay.getStyleClass().addAll("primary", "big");
        pay.setMaxWidth(Double.MAX_VALUE);
        pay.setOnAction(e -> Ui.attempt(this::pay));
        lastReceipt.setDisable(true);
        lastReceipt.setOnAction(e -> {
            ReceiptDialog.show(lastReceiptText);
            scan.requestFocus();
        });
        availability.setWrapText(true);
        receiptReminder.setWrapText(true);
        for (Label feedback : List.of(availability, status, receiptReminder)) {
            feedback.setMaxWidth(Double.MAX_VALUE);
            feedback.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
            feedback.visibleProperty().bind(feedback.textProperty().isNotEmpty());
            feedback.managedProperty().bind(feedback.visibleProperty());
        }
        lastReceipt.visibleProperty().bind(lastReceipt.disableProperty().not());
        lastReceipt.managedProperty().bind(lastReceipt.visibleProperty());
        VBox totals = new VBox(6,
                Ui.heading("Totals"),
                row("Subtotal", subtotal), row("Discount", discount), row("Tax", tax), row("Total", total),
                pay, shiftNotice, availability, status, lastReceipt, receiptReminder);
        totals.getStyleClass().add("totals-panel");

        VBox right = new VBox(8, Ui.heading("Cart"), cartTable, lineButtons, parking, totals);
        VBox.setVgrow(cartTable, Priority.ALWAYS);
        ScrollPane cartScroll = new ScrollPane(right);
        cartScroll.getStyleClass().add("page-scroll");
        cartScroll.setFitToWidth(true);
        cartScroll.setFitToHeight(true);
        cartScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        right.setPadding(new Insets(0, 0, 0, 12));

        setCenter(Ui.split(.46, left, cartScroll));
        setPadding(new Insets(12));
        onShow();
    }

    @Override
    public void onShow() {
        showBrowseList();
        refreshShift();
        refreshCart();
        refreshHeld();
    }

    /** The product list when nothing has been searched for: every product that is for sale, by name. */
    private void showBrowseList() {
        results.getItems().setAll(services.inventory().listProducts().stream().filter(Product::active).toList());
        searchSummary.setText(results.getItems().size() + " products");
    }

    /** True if the cashier has started a sale that has not been completed. The shell asks before leaving it. */
    boolean hasItems() {
        return !cart.isEmpty();
    }

    void clearSessionReceipt() {
        lastReceiptText = null;
        lastReceipt.setDisable(true);
        receiptReminder.setText("");
    }

    private void refreshShift() {
        Optional<Shift> shift = services.shifts().currentShift();
        if (shift.isEmpty()) {
            shiftNotice.setText("No shift is open. Open one on the Shift screen before selling.");
        } else {
            shiftNotice.setText("Shift open since " + Ui.dateTime(shift.get().openedAt()) + ".");
        }
        pay.setDisable(shift.isEmpty() || cart.isEmpty());
        hold.setDisable(cart.isEmpty());
    }

    private void scanOrSearch() {
        String text = scan.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        Optional<Product> byCode = services.inventory().findByCode(text);
        if (byCode.isPresent()) {
            scan.clear();
            showBrowseList();
            addToCart(byCode.get());
            return;
        }
        List<Product> matches = services.inventory().search(text);
        results.getItems().setAll(matches);
        searchSummary.setText(matches.size() + " matches for \"" + text + "\"");
        results.getSelectionModel().selectFirst();
        if (matches.size() == 1) {
            scan.clear();
            showBrowseList();
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
        scan.clear();
        cartChanged();
        for (int i = 0; i < cartTable.getItems().size(); i++) {
            if (cartTable.getItems().get(i).line().product.id() == product.id()) {
                cartTable.getSelectionModel().select(i);
                cartTable.scrollTo(i);
                break;
            }
        }
        scan.requestFocus();
    }

    private CartLine selectedLine() {
        Row row = cartTable.getSelectionModel().getSelectedItem();
        if (row == null) {
            throw new BusinessException("Select a line in the cart first");
        }
        return row.line();
    }

    private void changeQuantity() {
        CartLine line = selectedLine();
        TextField qty = new TextField(String.valueOf(line.qty));
        boolean changed = FormDialog.run(null, "Quantity", "Quantity for " + line.product.name(), qty, "Set quantity", () -> {
            int value = Ui.parseWhole(qty.getText(), "Quantity");
            int previous = line.qty;
            line.qty = value;
            try {
                refreshCartStrict();
            } catch (BusinessException e) {
                line.qty = previous;
                throw e;
            }
        });
        if (changed) {
            cartChanged();
        }
    }

    private void adjustQuantity(int delta) {
        CartLine line = selectedLine();
        if (line.qty + delta < 1) {
            status.setText("Use Remove to take this item out of the cart.");
            return;
        }
        int previous = line.qty;
        line.qty += delta;
        try {
            refreshCartStrict();
        } catch (BusinessException e) {
            line.qty = previous;
            throw e;
        }
        status.setText("");
        cartChanged();
    }

    /**
     * Changes a line's discount. A discount above the store's threshold needs a manager's approval, which is asked for
     * here, when the discount is applied, rather than at payment.
     */
    private void changeDiscount() {
        CartLine line = selectedLine();
        TextField percent = new TextField(Ui.plainPercent(line.discountBp));
        AtomicReference<Integer> chosen = new AtomicReference<>();
        boolean entered = FormDialog.run(null, "Discount", "Discount % for " + line.product.name(), percent,
                "Continue", () -> {
                    int bp = Ui.parsePercentToBp(percent.getText(), "Discount");
                    if (bp < 0 || bp > 10_000) {
                        throw new BusinessException("Discount must be between 0% and 100%");
                    }
                    chosen.set(bp);
                });
        if (!entered) {
            return;
        }

        int bp = chosen.get();
        if (bp > services.checkout().discountApprovalThresholdBp() && approverId == null) {
            Optional<User> approver = ApproverDialog.ask(services,
                    "Discounts above " + Ui.percent(services.checkout().discountApprovalThresholdBp()) + " need approval");
            if (approver.isEmpty()) {
                return;
            }
            approverId = approver.get().id();
        }

        int previous = line.discountBp;
        line.discountBp = bp;
        try {
            refreshCartStrict();
        } catch (BusinessException e) {
            line.discountBp = previous;
            throw e;
        }
        cartChanged();
    }

    private void removeSelected() {
        CartLine line = selectedLine();
        cart.remove(line);
        cartChanged();
    }

    private void clearCart() {
        if (!cart.isEmpty() && !Ui.confirm("Clear the whole cart?")) {
            return;
        }
        cart.clear();
        approverId = null;
        cartChanged();
    }

    /**
     * Parks the whole cart so another customer can be served first. Nothing is reserved: the cart is priced again, and
     * stock checked again, when it is recalled.
     */
    private void holdSale() {
        if (cart.isEmpty()) {
            throw new BusinessException("The cart is empty");
        }
        TextField label = new TextField();
        label.setPromptText("Optional, e.g. Table 4 or a customer's name");
        boolean held = FormDialog.run(null, "Hold sale", "Park this sale", label, "Hold sale",
                () -> services.holds().hold(toItems(), label.getText()));
        if (!held) {
            return;
        }
        cart.clear();
        approverId = null;
        status.setText("Sale held. Recall it with F7.");
        cartChanged();
        refreshHeld();
    }

    /**
     * Brings a held sale onto the sale on screen. The held lines are merged into the cart, and prices are read again.
     * Anything that can no longer be sold as it stands is listed once the cart has loaded, so it can be changed before payment.
     */
    private void recallSale() {
        List<HeldCart> held = services.holds().list();
        if (held.isEmpty()) {
            throw new BusinessException("No sales are on hold");
        }

        TableView<HeldCart> table = Ui.table();
        table.getColumns().addAll(
                Columns.text("Label", 180, h -> h.label() == null ? "(no label)" : h.label()),
                Columns.text("Cashier", 100, HeldCart::cashier),
                Columns.text("Waiting", 90, h -> waiting(h.createdAt())),
                Columns.whole("Lines", 60, HeldCart::lineCount),
                Columns.money("Total", 100, HeldCart::totalCents),
                Columns.dateTime("Held at", 150, HeldCart::createdAt));
        table.setItems(FXCollections.observableArrayList(held));
        table.getSelectionModel().selectFirst();
        table.setPrefHeight(240);
        Button discard = new Button("Discard selected");
        discard.getStyleClass().add("danger-quiet");
        discard.setOnAction(e -> Ui.attempt(() -> discardHeld(table)));

        AtomicReference<HeldCart> chosen = new AtomicReference<>();
        boolean recalling = FormDialog.run(null, "Recall sale", "Choose a held sale to add to this sale",
                new VBox(8, table, discard),
                "Recall sale", () -> {
                    HeldCart selected = table.getSelectionModel().getSelectedItem();
                    if (selected == null) {
                        throw new BusinessException("Select a held sale first");
                    }
                    chosen.set(selected);
                });
        if (!recalling) {
            return;
        }

        Recalled recalled = recallWithApproval(chosen.get().id());
        if (recalled == null) {
            return;
        }
        // The service returns the whole cart after the merge, so the cart is rebuilt from that list.
        cart.clear();
        for (CartItem item : recalled.items()) {
            services.inventory().findProduct(item.productId()).ifPresent(product -> {
                CartLine line = new CartLine(product, item.qty());
                line.discountBp = item.discountBp();
                cart.add(line);
            });
        }
        cartChanged();
        refreshHeld();
        String heldName = chosen.get().label() == null ? "held sale" : chosen.get().label();
        status.setText("Recalled " + heldName + ": " + recalled.linesAdded() + " line(s) added, "
                + recalled.linesCombined() + " combined.");
        if (!recalled.problems().isEmpty()) {
            Ui.showInfo("Check these before taking payment:\n\n• " + String.join("\n• ", recalled.problems()));
        }
    }

    /**
     * Recalls a held sale, asking for a manager's approval when the merged cart needs one. The approver is kept on the cart.
     * Returns null if the approval is refused, which leaves the held sale where it was.
     */
    private Recalled recallWithApproval(long holdId) {
        while (true) {
            try {
                return services.holds().recall(holdId, toItems(), approverId);
            } catch (ApprovalRequiredException e) {
                Optional<User> approver = ApproverDialog.ask(services, e.getMessage());
                if (approver.isEmpty()) {
                    return null;
                }
                approverId = approver.get().id();
            }
        }
    }

    /** Throws away the selected held sale after confirming. The recall list stays open for the rest. */
    private void discardHeld(TableView<HeldCart> table) {
        HeldCart selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            throw new BusinessException("Select a held sale first");
        }
        if (!Ui.confirm("Discard this held sale? It cannot be recovered.")) {
            return;
        }
        services.holds().discard(selected.id());
        table.getItems().remove(selected);
        refreshHeld();
    }

    /** Shows how many sales are parked, so a held sale is not forgotten. */
    private void refreshHeld() {
        int count = services.holds().list().size();
        recall.setText(count == 0 ? "Recall (F7)" : "Recall (F7) · " + count + " held");
        recall.setDisable(count == 0);
        onHeldChanged.run();
    }

    /** How long a held sale has been waiting, in words a cashier reads at a glance. */
    static String waiting(Instant since) {
        long minutes = Duration.between(since, Instant.now()).toMinutes();
        if (minutes < 1) {
            return "Just now";
        }
        if (minutes < 60) {
            return minutes + " min";
        }
        return minutes / 60 + " h " + minutes % 60 + " min";
    }

    /** Any change to the cart: a new payment attempt starts, and the totals are priced again. */
    private void cartChanged() {
        paymentKey = null;
        refreshCart();
    }

    /** Re-prices the cart. Errors from the service propagate to the caller, who decides what to show. */
    private void refreshCartStrict() {
        if (cart.isEmpty()) {
            quote = EMPTY_QUOTE;
        } else {
            quote = services.checkout().quote(toItems());
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < cart.size(); i++) {
            rows.add(new Row(cart.get(i), quote.lines().get(i)));
        }
        int selected = cartTable.getSelectionModel().getSelectedIndex();
        cartTable.getItems().setAll(rows);
        if (selected >= 0 && selected < rows.size()) {
            cartTable.getSelectionModel().select(selected);
        }
        setTotals(quote);
        refreshShift();
    }

    /** Re-prices the cart for display, showing a problem in the status line rather than failing the caller. */
    private void refreshCart() {
        try {
            refreshCartStrict();
            services.checkout().validateAvailability(toItems());
            availability.setText("");
        } catch (BusinessException e) {
            availability.setText(e.getMessage());
            pay.setDisable(true);
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
        if (services.shifts().currentShift().isEmpty()) {
            throw new BusinessException("Open a shift on the Shift screen before taking payment.");
        }
        refreshCart();
        if (!availability.getText().isEmpty()) {
            return;
        }
        List<CartItem> items = toItems();
        long totalCents = quote.totalCents();

        if (paymentKey == null) {
            paymentKey = UUID.randomUUID().toString();
        }
        SaleDetails details;
        if (totalCents == 0) {
            if (!Ui.confirm("Complete this sale with nothing to pay?")) {
                return;
            }
            details = completeSale(items, List.of());
        } else {
            Optional<SaleDetails> asked = PaymentDialog.ask(services, totalCents,
                    tenders -> completeSale(items, tenders));
            if (asked.isEmpty()) {
                refreshCart();
                return;
            }
            details = asked.get();
        }
        if (details == null) {
            return;
        }

        String cashier = services.session().requireUser().username();
        cart.clear();
        approverId = null;
        paymentKey = null;
        clearSessionReceipt();
        status.setText("Sale " + details.sale().receiptNo() + " completed.");
        lastReceiptText = ReceiptFormatter.format(services.storeName(),
                services.storeSettings().current().receiptFooter(), details, cashier);
        lastReceipt.setDisable(false);
        receiptReminder.setText(details.giftCardCodes().isEmpty() ? ""
                : "Gift cards issued. View or save the receipt to give the customer their codes.");
        showBrowseList();
        refreshCart();
        scan.requestFocus();
    }

    /** Completes the sale. Asks for manager approval if the service still needs it. Returns null if cancelled. */
    private SaleDetails completeSale(List<CartItem> items, List<PaymentRequest> tenders) {
        while (true) {
            try {
                return services.checkout().checkout(items, tenders, approverId, paymentKey);
            } catch (ApprovalRequiredException e) {
                Optional<User> approver = ApproverDialog.ask(services, e.getMessage());
                if (approver.isEmpty()) {
                    return null;
                }
                approverId = approver.get().id();
            }
        }
    }

    private static HBox row(String label, Label value) {
        Label name = new Label(label);
        name.setMinWidth(90);
        value.setMinWidth(110);
        value.getStyleClass().add("bold");
        return new HBox(8, name, value);
    }
}
