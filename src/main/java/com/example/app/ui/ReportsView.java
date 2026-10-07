package com.example.app.ui;

import com.example.app.error.BusinessException;
import com.example.app.repository.AuditRepository.AuditEntry;
import com.example.app.service.AppServices;
import com.example.app.service.ReportService.LedgerEntry;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.time.LocalDate;

/** Sales ledger for a date range with CSV export, inventory valuation, and the audit log. */
final class ReportsView extends VBox {

    private final AppServices services;
    private final DatePicker from = new DatePicker(LocalDate.now().minusDays(6));
    private final DatePicker to = new DatePicker(LocalDate.now());
    private final TableView<LedgerEntry> ledger = Ui.table();
    private final TableView<AuditEntry> audit = Ui.table();
    private final Label summary = Ui.muted("");

    ReportsView(AppServices services) {
        this.services = services;
        setSpacing(10);
        setPadding(new Insets(16));

        ledger.getColumns().addAll(
                Ui.<LedgerEntry>column("When", 150, e -> Ui.dateTime(e.at())),
                Ui.<LedgerEntry>column("Type", 80, LedgerEntry::type),
                Ui.<LedgerEntry>column("Reference", 260, LedgerEntry::reference),
                Ui.<LedgerEntry>column("Amount", 100, e -> Ui.money(e.amountCents())),
                Ui.<LedgerEntry>column("Running total", 120, e -> Ui.money(e.runningTotalCents())));
        VBox.setVgrow(ledger, Priority.ALWAYS);

        audit.getColumns().addAll(
                Ui.<AuditEntry>column("When", 150, e -> Ui.dateTime(e.createdAt())),
                Ui.<AuditEntry>column("User #", 70, e -> e.userId() == null ? "" : String.valueOf(e.userId())),
                Ui.<AuditEntry>column("Action", 140, AuditEntry::action),
                Ui.<AuditEntry>column("Entity", 150, e -> e.entity() + (e.entityId() == null ? "" : " " + e.entityId())),
                Ui.<AuditEntry>column("Detail", 320, e -> e.detail() == null ? "" : e.detail()));

        Button show = new Button("Show ledger");
        show.setOnAction(e -> Ui.attempt(this::showLedger));
        Button exportLedger = new Button("Export ledger CSV...");
        exportLedger.setOnAction(e -> Ui.attempt(this::exportLedger));
        Button exportStock = new Button("Export stock valuation CSV...");
        exportStock.setOnAction(e -> Ui.attempt(this::exportStock));

        HBox range = new HBox(8, new Label("From"), from, new Label("To"), to, show, exportLedger, exportStock);

        Tab sales = new Tab("Sales ledger", new VBox(8, range, summary, ledger));
        sales.setClosable(false);
        Tab auditTab = new Tab("Audit log", new VBox(8, Ui.muted("Most recent 200 sensitive actions."), audit));
        auditTab.setClosable(false);
        auditTab.setOnSelectionChanged(e -> {
            if (auditTab.isSelected()) {
                Ui.attempt(this::loadAudit);
            }
        });
        TabPane tabs = new TabPane(sales, auditTab);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        getChildren().addAll(Ui.heading("Reports"), tabs);

        Ui.attempt(this::showLedger);
    }

    private void showLedger() {
        LocalDate start = requireDate(from, "From");
        LocalDate end = requireDate(to, "To");
        var entries = services.reports().ledger(start, end);
        ledger.setItems(FXCollections.observableArrayList(entries));
        long total = entries.isEmpty() ? 0 : entries.get(entries.size() - 1).runningTotalCents();
        summary.setText(entries.size() + " entries. Net total " + Ui.money(total) + ".");
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

    private static LocalDate requireDate(DatePicker picker, String label) {
        if (picker.getValue() == null) {
            throw new BusinessException(label + " date is required");
        }
        return picker.getValue();
    }
}
