package com.example.app.ui;

import com.example.app.domain.AuditEntry;
import com.example.app.domain.MoneyFormat;
import com.example.app.domain.Shift;
import com.example.app.domain.User;
import com.example.app.error.ApprovalRequiredException;
import com.example.app.error.BusinessException;
import com.example.app.service.AppServices;
import com.example.app.service.CheckoutService.SaleDetails;
import com.example.app.service.ReportService.Dashboard;
import com.example.app.service.ReportService.DailyTotal;
import com.example.app.service.ReportService.LedgerEntry;
import com.example.app.service.ReportService.SaleSummary;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

import java.io.File;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Dashboard, sales history, ledger, audit log, and CSV exports for a chosen date range. */
final class ReportsView extends VBox implements Screen {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private final AppServices services;
    private final DatePicker from = new DatePicker(LocalDate.now().minusDays(6));
    private final DatePicker to = new DatePicker(LocalDate.now());
    private final Label summary = Ui.muted("");
    private final Label heroValue = new Label();
    private final Label heroCaption = Ui.muted("");
    private final Label transactions = new Label();
    private final Label averageBasket = new Label();
    private final Label refunds = new Label();
    private final Label voids = new Label();
    private final Label lowStock = new Label();
    private final Label chartEmpty = Ui.muted("No sales in this period.");
    private final BarChart<String, Number> chart = new BarChart<>(new CategoryAxis(), new NumberAxis());
    private final XYChart.Series<String, Number> series = new XYChart.Series<>();
    private final TableView<DailyTotal> dailyTable = Ui.table();
    private final TableView<SaleSummary> sales = Ui.table();
    private final TableView<LedgerEntry> ledger = Ui.table();
    private final TableView<AuditEntry> audit = Ui.table();
    private final Button voidButton = new Button("Void sale...");
    private final Label voidHint = Ui.muted("");
    private int dayCount = 1;

    ReportsView(AppServices services) {
        this.services = services;
        setSpacing(12);
        setPadding(new Insets(16));

        Button show = new Button("Show");
        show.getStyleClass().add("primary");
        show.setOnAction(e -> Ui.attempt(this::load));
        Button exportLedger = new Button("Export ledger CSV...");
        exportLedger.setOnAction(e -> Ui.attempt(this::exportLedger));
        Button exportStock = new Button("Export stock valuation CSV...");
        exportStock.setOnAction(e -> Ui.attempt(this::exportStock));
        HBox range = new HBox(8, new Label("From"), from, new Label("To"), to, show, exportLedger, exportStock);

        getChildren().addAll(Ui.heading("Reports"), range, summary, tabs());
        VBox.setVgrow(getChildren().get(getChildren().size() - 1), Priority.ALWAYS);
        onShow();
    }

    private TabPane tabs() {
        Tab dashboard = new Tab("Dashboard", dashboardPane());
        Tab salesTab = new Tab("Sales", salesPane());
        Tab ledgerTab = new Tab("Ledger", ledgerPane());
        Tab auditTab = new Tab("Audit log", auditPane());
        for (Tab tab : List.of(dashboard, salesTab, ledgerTab, auditTab)) {
            tab.setClosable(false);
        }
        auditTab.setOnSelectionChanged(e -> {
            if (auditTab.isSelected()) {
                Ui.attempt(this::loadAudit);
            }
        });
        TabPane tabs = new TabPane(dashboard, salesTab, ledgerTab, auditTab);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        return tabs;
    }

    private Node dashboardPane() {
        VBox hero = new VBox(4, heroCaption, heroValue);
        hero.getStyleClass().add("card");
        hero.setMinWidth(260);
        heroValue.getStyleClass().add("hero-value");

        HBox tiles = new HBox(12,
                tile("Transactions", transactions),
                tile("Average basket", averageBasket),
                tile("Refunds", refunds),
                tile("Voids", voids),
                tile("Low-stock products", lowStock));
        HBox.setHgrow(tiles, Priority.ALWAYS);
        HBox top = new HBox(16, hero, tiles);

        chart.setTitle("Net sales per day");
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.getData().add(series);
        chart.setMinHeight(280);
        NumberAxis money = (NumberAxis) chart.getYAxis();
        money.setTickLabelFormatter(wholeMoney());
        money.setForceZeroInRange(true);
        money.setMinorTickVisible(false);
        // Bars stay at most 24px thick, with a gap between them, however wide the window is.
        chart.widthProperty().addListener((obs, was, now) -> fitBars());

        dailyTable.getColumns().addAll(
                Columns.text("Day", 180, d -> DAY.format(d.day())),
                Columns.money("Net sales", 120, DailyTotal::netCents));
        dailyTable.setPlaceholder(new Label("No sales in this period."));
        dailyTable.setPrefHeight(180);

        VBox pane = new VBox(14, top, chartEmpty, chart, Ui.muted("The same figures, as a table:"), dailyTable);
        pane.setPadding(new Insets(12, 0, 0, 0));
        VBox.setVgrow(chart, Priority.ALWAYS);
        return pane;
    }

    private static VBox tile(String label, Label value) {
        Label caption = new Label(label);
        caption.getStyleClass().add("stat-label");
        caption.setWrapText(true);
        value.getStyleClass().add("stat-value");
        VBox box = new VBox(4, caption, value);
        box.getStyleClass().add("card");
        box.setMinWidth(120);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private Node salesPane() {
        sales.getColumns().addAll(
                Columns.text("Receipt", 110, s -> s.sale().receiptNo()),
                Columns.dateTime("When", 140, s -> s.sale().createdAt()),
                Columns.text("Cashier", 110, SaleSummary::cashier),
                Columns.money("Total", 110, s -> s.sale().totalCents()),
                Columns.money("Refunded", 110, SaleSummary::refundedCents));
        sales.setPlaceholder(new Label("No sales in this period."));
        Button reprint = new Button("Reprint receipt");
        reprint.setOnAction(e -> Ui.attempt(this::reprintSelected));
        voidButton.setOnAction(e -> Ui.attempt(this::voidSelected));
        voidButton.setDisable(true);
        sales.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> updateVoidButton());
        HBox actions = new HBox(8, reprint, voidButton, voidHint);
        VBox pane = new VBox(8,
                Ui.muted("Select a sale to reprint its receipt or void it. Refunds are made from the Refunds screen."),
                actions, sales);
        pane.setPadding(new Insets(12, 0, 0, 0));
        VBox.setVgrow(sales, Priority.ALWAYS);
        return pane;
    }

    private Node ledgerPane() {
        ledger.getColumns().addAll(
                Columns.dateTime("When", 150, LedgerEntry::at),
                Columns.text("Type", 80, LedgerEntry::type),
                Columns.text("Reference", 260, LedgerEntry::reference),
                Columns.money("Amount", 100, LedgerEntry::amountCents),
                Columns.money("Running total", 120, LedgerEntry::runningTotalCents));
        VBox pane = new VBox(8, ledger);
        pane.setPadding(new Insets(12, 0, 0, 0));
        VBox.setVgrow(ledger, Priority.ALWAYS);
        return pane;
    }

    private Node auditPane() {
        audit.getColumns().addAll(
                Columns.dateTime("When", 150, AuditEntry::createdAt),
                Columns.text("User #", 70, e -> e.userId() == null ? "" : String.valueOf(e.userId())),
                Columns.text("Action", 140, AuditEntry::action),
                Columns.text("Entity", 150, e -> e.entity() + (e.entityId() == null ? "" : " " + e.entityId())),
                Columns.text("Detail", 320, e -> e.detail() == null ? "" : e.detail()));
        VBox pane = new VBox(8, Ui.muted("Most recent 200 sensitive actions."), audit);
        pane.setPadding(new Insets(12, 0, 0, 0));
        VBox.setVgrow(audit, Priority.ALWAYS);
        return pane;
    }

    @Override
    public void onShow() {
        Ui.attempt(this::load);
    }

    /** Loads everything for the chosen range in one go, so every tab shows the same slice. */
    private void load() {
        LocalDate start = requireDate(from, "From");
        LocalDate end = requireDate(to, "To");
        Dashboard board = services.reports().dashboard(start, end);
        showDashboard(board);
        sales.setItems(FXCollections.observableArrayList(services.reports().salesBetween(start, end)));
        updateVoidButton();
        var entries = services.reports().ledger(start, end);
        ledger.setItems(FXCollections.observableArrayList(entries));
        summary.setText(board.transactions() + " sale(s), net " + Ui.money(board.netSalesCents())
                + " in the period. Ledger closing balance " + Ui.money(
                entries.isEmpty() ? 0 : entries.get(entries.size() - 1).runningTotalCents()) + ".");
    }

    private void showDashboard(Dashboard board) {
        heroCaption.setText("Net sales in this period");
        heroValue.setText(Ui.money(board.netSalesCents()));
        transactions.setText(String.valueOf(board.transactions()));
        averageBasket.setText(Ui.money(board.averageBasketCents()));
        refunds.setText(Ui.money(board.refundsCents()));
        voids.setText(Ui.money(board.voidsCents()));
        lowStock.setText(String.valueOf(board.lowStockCount()));

        series.getData().clear();
        dayCount = Math.max(1, board.daily().size());
        for (DailyTotal day : board.daily()) {
            XYChart.Data<String, Number> bar = new XYChart.Data<>(DAY.format(day.day()), day.netCents() / 100.0);
            series.getData().add(bar);
            bar.nodeProperty().addListener((obs, was, node) -> {
                if (node != null) {
                    Tooltip.install(node, new Tooltip(DAY.format(day.day()) + ": " + Ui.money(day.netCents())));
                }
            });
        }
        chartEmpty.setVisible(board.daily().isEmpty());
        chartEmpty.setManaged(board.daily().isEmpty());
        chart.setVisible(!board.daily().isEmpty());
        chart.setManaged(!board.daily().isEmpty());
        dailyTable.setItems(FXCollections.observableArrayList(board.daily()));
        fitBars();
    }

    /** Sets the category gap so each bar is at most 24px thick, with at least a 2px gap between neighbours. */
    private void fitBars() {
        double width = chart.getWidth();
        if (width <= 0) {
            return;
        }
        double perDay = (width - 90) / dayCount;
        chart.setCategoryGap(Math.max(2, perDay - 24));
    }

    private void reprintSelected() {
        SaleSummary selected = sales.getSelectionModel().getSelectedItem();
        if (selected == null) {
            throw new BusinessException("Select a sale first");
        }
        SaleDetails details = services.checkout().saleDetails(selected.sale().id());
        ReceiptDialog.show(ReceiptFormatter.format(services.storeName(),
                services.storeSettings().current().receiptFooter(), details, selected.cashier()));
    }

    /**
     * A void is possible on a sale from the open shift that still has something left to refund. The hint says why the
     * button is unavailable.
     */
    private void updateVoidButton() {
        SaleSummary selected = sales.getSelectionModel().getSelectedItem();
        Optional<Shift> open = services.shifts().currentShift();
        boolean inOpenShift = selected != null && open.isPresent() && selected.sale().shiftId() == open.get().id();
        boolean noPayment = selected != null && selected.sale().totalCents() == 0;
        boolean fullyRefunded = selected != null && !noPayment && selected.refundedCents() >= selected.sale().totalCents();
        boolean allowed = inOpenShift && !noPayment && !fullyRefunded;
        voidButton.setDisable(!allowed);
        String hint;
        if (selected == null || allowed) {
            hint = "";
        } else if (noPayment) {
            hint = "This sale took no payment, so there is nothing to void.";
        } else if (fullyRefunded) {
            hint = "Already fully refunded, so there is nothing left to void.";
        } else {
            hint = "Only a sale from the open shift can be voided.";
        }
        voidHint.setText(hint);
    }

    /**
     * Voids the selected sale: items still on it go back into stock, and the money still owed goes back to the original
     * payment methods. The service checks the shift and everything else. A cashier's void needs a manager's approval.
     */
    private void voidSelected() {
        SaleSummary selected = sales.getSelectionModel().getSelectedItem();
        if (selected == null) {
            throw new BusinessException("Select a sale first");
        }
        String receipt = selected.sale().receiptNo();
        TextField reasonField = new TextField();
        reasonField.setPromptText("Why is this sale being voided?");
        AtomicReference<String> reason = new AtomicReference<>();
        boolean entered = FormDialog.run(null, "Void sale", "Void " + receipt, reasonField, "Continue", () -> {
            String text = reasonField.getText() == null ? "" : reasonField.getText().trim();
            if (text.isEmpty()) {
                throw new BusinessException("A reason is required to void a sale");
            }
            reason.set(text);
        });
        if (!entered || !Ui.confirm("Void " + receipt + "? Items still on it go back into stock, and the money still owed "
                + "goes back to the original payment methods.")) {
            return;
        }

        Long approverId = null;
        while (true) {
            try {
                services.refunds().voidSale(selected.sale().id(), reason.get(), approverId);
                break;
            } catch (ApprovalRequiredException e) {
                Optional<User> approver = ApproverDialog.ask(services, e.getMessage());
                if (approver.isEmpty()) {
                    return;
                }
                approverId = approver.get().id();
            }
        }
        Toast.show("Sale " + receipt + " voided");
        load();
    }

    private void loadAudit() {
        audit.setItems(FXCollections.observableArrayList(services.audit().recent(200)));
    }

    private void exportLedger() {
        LocalDate start = requireDate(from, "From");
        LocalDate end = requireDate(to, "To");
        File file = chooseFile("ledger.csv");
        if (file != null) {
            services.reports().exportLedgerCsv(start, end, file.toPath());
            Ui.showInfo("Ledger exported to " + file.getAbsolutePath());
        }
    }

    private void exportStock() {
        File file = chooseFile("stock-valuation.csv");
        if (file != null) {
            services.reports().exportInventoryValuationCsv(file.toPath());
            Ui.showInfo("Stock valuation exported to " + file.getAbsolutePath());
        }
    }

    private File chooseFile(String suggestedName) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export CSV");
        chooser.setInitialFileName(suggestedName);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        return chooser.showSaveDialog(getScene() == null ? null : getScene().getWindow());
    }

    /** Whole-currency tick labels on the value axis, in the store's locale. Ticks are for scale, not precision. */
    private static StringConverter<Number> wholeMoney() {
        NumberFormat format = NumberFormat.getIntegerInstance(Locale.forLanguageTag(MoneyFormat.localeTag()));
        return new StringConverter<>() {
            @Override
            public String toString(Number value) {
                return format.format(value.doubleValue());
            }

            @Override
            public Number fromString(String text) {
                return 0;
            }
        };
    }

    private static LocalDate requireDate(DatePicker picker, String label) {
        if (picker.getValue() == null) {
            throw new BusinessException(label + " date is required");
        }
        return picker.getValue();
    }
}
