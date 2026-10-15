package com.example.app.ui;

import com.example.app.domain.MoneyFormat;
import com.example.app.domain.Tender;
import com.example.app.domain.TenderCodes;
import com.example.app.domain.TenderKind;
import com.example.app.error.BusinessException;
import com.example.app.service.AppServices;
import com.example.app.service.SettingsService.StoreSettings;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.List;

/**
 * Settings for the owner. Store settings are kept in the database and apply on every terminal; appearance and the idle
 * lock are kept on this computer only. The page scrolls when the window is too short for all of it.
 */
final class SettingsView extends ScrollPane implements Screen {

    private final AppServices services;
    private final UiPrefs prefs;

    private final TextField storeName = new TextField();
    private final ChoiceBox<String> currency = new ChoiceBox<>();
    private final ChoiceBox<String> locale = new ChoiceBox<>();
    private final TextField discountLevel = new TextField();
    private final CheckBox allowNegative = new CheckBox("Allow selling more than is in stock");
    private final TextField footer = new TextField();
    private final Label storeMessage = Ui.muted("");

    private final ChoiceBox<ThemeManager.Mode> theme = new ChoiceBox<>(FXCollections.observableArrayList(ThemeManager.Mode.values()));
    private final ChoiceBox<ThemeManager.Density> density = new ChoiceBox<>(FXCollections.observableArrayList(ThemeManager.Density.values()));
    private final TextField idleMinutes = new TextField();
    private final Label appearanceMessage = Ui.muted("");

    private final Label dataMessage = Ui.muted("");

    private final TableView<Tender> tenderTable = Ui.table();
    private final Button addTender = new Button("Add payment type...");
    private final Button renameTender = new Button("Rename...");
    private final Button toggleTender = new Button("Turn off");
    private final Button drawerTender = new Button("Count in drawer");
    private final Button moveTenderUp = new Button("Move up");
    private final Button moveTenderDown = new Button("Move down");
    private final Button deleteTender = new Button("Delete...");

    SettingsView(AppServices services, UiPrefs prefs) {
        this.services = services;
        this.prefs = prefs;

        currency.setItems(FXCollections.observableArrayList(MoneyFormat.supportedCurrencies()));
        locale.setItems(FXCollections.observableArrayList(MoneyFormat.LOCALES));
        theme.setConverter(labelled(ThemeManager.Mode.SYSTEM, "Follow the system", ThemeManager.Mode.LIGHT, "Light",
                ThemeManager.Mode.DARK, "Dark"));
        density.setConverter(labelled(ThemeManager.Density.COMFORTABLE, "Comfortable", ThemeManager.Density.COMPACT,
                "Compact", ThemeManager.Density.TOUCH, "Touch screen"));
        footer.setPromptText("Printed at the bottom of every receipt");
        discountLevel.setTooltip(new javafx.scene.control.Tooltip(
                "Discounts above this percentage need a manager's approval"));
        idleMinutes.setTooltip(new javafx.scene.control.Tooltip("Minutes without input before the till locks. 0 turns it off."));

        Button saveStore = new Button("Save store settings");
        saveStore.getStyleClass().add("primary");
        saveStore.setOnAction(e -> Ui.attempt(this::saveStore));
        Button saveAppearance = new Button("Save appearance");
        saveAppearance.setOnAction(e -> Ui.attempt(this::saveAppearance));
        Button stockCheck = new Button("Check stock figures");
        stockCheck.setOnAction(e -> Ui.attempt(this::checkStock));
        Button backup = new Button("Back up now");
        backup.setOnAction(e -> Ui.attempt(this::backUpNow));

        var store = Ui.grid();
        store.addRow(0, new Label("Store name"), storeName);
        store.addRow(1, new Label("Currency"), currency);
        store.addRow(2, new Label("Number format"), locale);
        store.addRow(3, new Label("Manager approval above"), withSuffix(discountLevel, "% discount"));
        store.add(allowNegative, 1, 4);
        store.addRow(5, new Label("Receipt footer"), footer);
        VBox storeCard = new VBox(10, Ui.heading("Store"), store, new HBox(8, saveStore), storeMessage);
        storeCard.getStyleClass().add("card");

        var look = Ui.grid();
        look.addRow(0, new Label("Colour scheme"), theme);
        look.addRow(1, new Label("Density"), density);
        look.addRow(2, new Label("Lock after idle"), withSuffix(idleMinutes, "minutes"));
        VBox appearanceCard = new VBox(10, Ui.heading("This computer"), look, new HBox(8, saveAppearance),
                appearanceMessage);
        appearanceCard.getStyleClass().add("card");

        Label dataNote = Ui.muted("Stock figures are checked against the movement history. Backups are kept in the backups "
                + "folder, and the ten most recent are kept. To restore, close the app, then copy a backup's database "
                + "file back over the one in the data folder.");
        dataNote.setWrapText(true);
        VBox dataCard = new VBox(10, Ui.heading("Data"), dataNote, Ui.actions(stockCheck, backup), dataMessage);
        dataCard.getStyleClass().add("card");

        tenderTable.getColumns().addAll(
                Columns.whole("#", 50, Tender::sortOrder),
                Columns.text("Name", 200, Tender::name),
                Columns.text("Type", 230, t -> t.kind().label()),
                Columns.text("Status", 80, t -> t.enabled() ? "On" : "Off"),
                Columns.text("In drawer", 90, t -> t.countsInDrawer() ? "Yes" : "No"),
                Columns.text("Reference", 100, t -> t.requiresReference() ? "Required" : "Optional"));
        tenderTable.setPrefHeight(220);
        tenderTable.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> updateTenderButtons());
        addTender.setOnAction(e -> Ui.attempt(this::createTender));
        renameTender.setOnAction(e -> Ui.attempt(this::renameSelectedTender));
        toggleTender.setOnAction(e -> Ui.attempt(this::toggleSelectedTender));
        drawerTender.setOnAction(e -> Ui.attempt(this::toggleDrawerOfSelectedTender));
        moveTenderUp.setOnAction(e -> Ui.attempt(() -> {
            services.tenders().moveUp(selectedTender().code());
            reloadTenders();
        }));
        moveTenderDown.setOnAction(e -> Ui.attempt(() -> {
            services.tenders().moveDown(selectedTender().code());
            reloadTenders();
        }));
        deleteTender.setOnAction(e -> Ui.attempt(this::deleteSelectedTender));
        deleteTender.getStyleClass().add("danger-quiet");
        Label tenderNote = Ui.muted("Built-in cash is always on, and cash types always count in the drawer. A payment type "
                + "that has been used cannot be deleted; turn it off instead. A type that is off is not offered, but refunds "
                + "on old sales still work.");
        tenderNote.setWrapText(true);
        VBox tendersCard = new VBox(10, Ui.heading("Payment types"), tenderNote, tenderTable,
                Ui.actions(addTender, renameTender, toggleTender, moveTenderUp, moveTenderDown, drawerTender, deleteTender));
        tendersCard.getStyleClass().add("card");

        var row = Ui.split(.56, storeCard, appearanceCard);
        storeCard.setMinWidth(340);
        appearanceCard.setMinWidth(320);
        row.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        VBox content = new VBox(14, row, tendersCard, dataCard);
        content.setPadding(new Insets(16));
        getStyleClass().add("page-scroll");
        setContent(content);
        setFitToWidth(true);
        setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        onShow();
    }

    @Override
    public void onShow() {
        reloadTenders();
        StoreSettings current = services.storeSettings().current();
        storeName.setText(current.storeName());
        currency.setValue(current.currency());
        locale.setValue(current.locale());
        discountLevel.setText(Ui.plainPercent(current.discountApprovalBp()));
        allowNegative.setSelected(current.allowNegativeStock());
        footer.setText(current.receiptFooter());

        theme.setValue(prefs.themeMode());
        density.setValue(prefs.density());
        idleMinutes.setText(String.valueOf(prefs.idleLimit().toMinutes()));
    }

    private void saveStore() {
        StoreSettings input = new StoreSettings(
                storeName.getText(),
                currency.getValue(),
                locale.getValue(),
                Ui.parsePercentToBp(discountLevel.getText(), "Manager approval level"),
                allowNegative.isSelected(),
                footer.getText());
        services.storeSettings().update(input);
        storeMessage.setText("Store settings saved.");
        Toast.show("Store settings saved");
    }

    private void saveAppearance() {
        int minutes = Ui.parseWhole(idleMinutes.getText(), "Lock after idle");
        ThemeManager.Mode mode = theme.getValue();
        ThemeManager.Density chosenDensity = density.getValue();
        prefs.setThemeMode(mode);
        prefs.setDensity(chosenDensity);
        prefs.setIdleMinutes(minutes);
        prefs.save();
        ThemeManager.setMode(mode);
        ThemeManager.setDensity(chosenDensity);
        appearanceMessage.setText("Appearance saved. A new idle time applies from the next login.");
        Toast.show("Appearance saved");
    }

    private void checkStock() {
        List<String> problems = services.inventory().stockMismatches();
        if (problems.isEmpty()) {
            dataMessage.setText("All stock figures agree with the movement history.");
        } else {
            dataMessage.setText(problems.size() + " product(s) disagree with the movement history:\n"
                    + String.join("\n", problems));
        }
    }

    private void backUpNow() {
        services.backups().backupNow("manual").ifPresentOrElse(
                file -> dataMessage.setText("Backup written to " + file.toAbsolutePath()),
                () -> dataMessage.setText("Backups are not available for this database."));
    }

    /** Reloads the payment types, keeping the one that was selected selected. */
    private void reloadTenders() {
        Tender selected = tenderTable.getSelectionModel().getSelectedItem();
        String keep = selected == null ? null : selected.code();
        tenderTable.setItems(FXCollections.observableArrayList(services.tenders().list()));
        if (keep != null) {
            for (Tender tender : tenderTable.getItems()) {
                if (tender.code().equals(keep)) {
                    tenderTable.getSelectionModel().select(tender);
                    break;
                }
            }
        }
        updateTenderButtons();
    }

    /**
     * Enables only the actions that make sense for the selected payment type. Built-in cash cannot be turned off, and a cash
     * type cannot be taken out of the drawer.
     */
    private void updateTenderButtons() {
        Tender tender = tenderTable.getSelectionModel().getSelectedItem();
        boolean none = tender == null;
        int index = none ? -1 : tenderTable.getItems().indexOf(tender);
        renameTender.setDisable(none);
        toggleTender.setDisable(none || TenderCodes.CASH.equals(tender.code()));
        toggleTender.setText(none || tender.enabled() ? "Turn off" : "Turn on");
        drawerTender.setDisable(none || tender.isCash());
        drawerTender.setText(none || tender.countsInDrawer() ? "Stop counting in drawer" : "Count in drawer");
        moveTenderUp.setDisable(none || index <= 0);
        moveTenderDown.setDisable(none || index >= tenderTable.getItems().size() - 1);
        deleteTender.setDisable(none || tender.isBuiltIn());
    }

    private Tender selectedTender() {
        Tender tender = tenderTable.getSelectionModel().getSelectedItem();
        if (tender == null) {
            throw new BusinessException("Select a payment type first");
        }
        return tender;
    }

    private void createTender() {
        TextField name = new TextField();
        name.setPromptText("e.g. Voucher or Debit card");
        ChoiceBox<TenderKind> kind = new ChoiceBox<>(FXCollections.observableArrayList(TenderKind.values()));
        kind.setConverter(new StringConverter<>() {
            @Override
            public String toString(TenderKind value) {
                return value == null ? "" : value.label();
            }

            @Override
            public TenderKind fromString(String text) {
                return null;
            }
        });
        kind.setValue(TenderKind.EXTERNAL);
        var grid = Ui.grid();
        grid.addRow(0, new Label("Name"), name);
        grid.addRow(1, new Label("Type"), kind);
        VBox content = new VBox(8, grid, Ui.muted("Cash counts in the drawer and gives change. Every other type asks the "
                + "cashier for a reference: a card number, a wallet or voucher reference, or a gift card code."));
        boolean added = FormDialog.run(null, "New payment type", "Add a payment type", content, "Add",
                () -> services.tenders().create(name.getText(), kind.getValue()));
        if (added) {
            reloadTenders();
            Toast.show("Payment type added");
        }
    }

    private void renameSelectedTender() {
        Tender tender = selectedTender();
        TextField name = new TextField(tender.name());
        boolean renamed = FormDialog.run(null, "Rename payment type", "Rename " + tender.name(), name, "Rename",
                () -> services.tenders().rename(tender.code(), name.getText()));
        if (renamed) {
            reloadTenders();
        }
    }

    private void toggleSelectedTender() {
        Tender tender = selectedTender();
        services.tenders().setEnabled(tender.code(), !tender.enabled());
        reloadTenders();
    }

    private void toggleDrawerOfSelectedTender() {
        Tender tender = selectedTender();
        services.tenders().setCountsInDrawer(tender.code(), !tender.countsInDrawer());
        reloadTenders();
    }

    private void deleteSelectedTender() {
        Tender tender = selectedTender();
        if (!Ui.confirm("Delete the payment type " + tender.name() + "? This cannot be undone.")) {
            return;
        }
        services.tenders().delete(tender.code());
        reloadTenders();
    }

    private static HBox withSuffix(TextField field, String suffix) {
        field.setPrefColumnCount(6);
        field.setMinWidth(70);
        HBox.setHgrow(field, javafx.scene.layout.Priority.ALWAYS);
        return new HBox(8, field, new Label(suffix));
    }

    private static <T> StringConverter<T> labelled(Object... pairs) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                for (int i = 0; i < pairs.length; i += 2) {
                    if (pairs[i] == value) {
                        return (String) pairs[i + 1];
                    }
                }
                return String.valueOf(value);
            }

            @Override
            public T fromString(String text) {
                return null;
            }
        };
    }
}
