package com.example.app.ui;

import com.example.app.domain.MoneyFormat;
import com.example.app.domain.Role;
import com.example.app.domain.Shift;
import com.example.app.domain.TenderTotal;
import com.example.app.domain.User;
import com.example.app.service.AppServices;
import com.example.app.service.ShiftService.ShiftClose;
import com.example.app.service.ShiftService.ShiftSummary;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;

import java.util.Optional;

/** One drawer per terminal. Expected cash stays hidden until the blind count is complete. */
final class ShiftView extends VBox implements Screen {
    private static final Image ART = new Image(ShiftView.class.getResource("artwork/shift-panorama.png").toExternalForm());
    private final AppServices services;
    private final VBox content = new VBox(18);

    ShiftView(AppServices services) {
        this.services = services;
        setPadding(new Insets(16));
        getStyleClass().add("shift-page");
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("shift-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().add(scroll);
        onShow();
    }

    @Override
    public void onShow() {
        content.getChildren().clear();
        Optional<Shift> open = services.shifts().currentShift();
        content.getChildren().add(header(open.orElse(null)));
        if (open.isEmpty()) buildOpenForm();
        else buildSummary(open.get());
    }

    private StackPane header(Shift shift) {
        StackPane banner = new StackPane();
        banner.getStyleClass().add("shift-banner");
        banner.setMinHeight(148);
        banner.setPrefHeight(148);
        banner.setMaxHeight(148);
        banner.setBackground(new Background(new BackgroundImage(ART, BackgroundRepeat.NO_REPEAT,
                BackgroundRepeat.NO_REPEAT, new BackgroundPosition(javafx.geometry.Side.LEFT, .5, true,
                javafx.geometry.Side.TOP, .72, true),
                new BackgroundSize(100, 100, true, true, false, true))));
        Region shade = new Region();
        shade.getStyleClass().add("shift-banner-shade");
        Label title = styled("Shift", "shift-banner-title");
        Label caption = styled(shift == null ? "A fresh start for your store."
                : "Opened " + Ui.dateTime(shift.openedAt()) + " by " + services.auth().usernameOf(shift.userId()),
                "shift-banner-caption");
        caption.setWrapText(true);
        Label status = styled(shift == null ? "READY TO OPEN" : "SHIFT OPEN", "shift-status");
        VBox story = new VBox(7, status, title, caption);
        story.setAlignment(Pos.CENTER_LEFT);
        story.setPadding(new Insets(22, 28, 22, 28));
        banner.getChildren().addAll(shade, story);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(banner.widthProperty());
        clip.heightProperty().bind(banner.heightProperty());
        clip.setArcWidth(28); clip.setArcHeight(28);
        banner.setClip(clip);
        return banner;
    }

    private void buildOpenForm() {
        TextField floatField = new TextField(MoneyFormat.formatForInput(20_000));
        floatField.setId("shift-opening-float");
        floatField.setMaxWidth(Double.MAX_VALUE);
        FlowPane quick = Ui.actions();
        for (long amount : new long[]{10_000, 20_000, 30_000, 50_000}) {
            Button choice = new Button(Ui.money(amount));
            choice.setOnAction(e -> floatField.setText(MoneyFormat.formatForInput(amount)));
            quick.getChildren().add(choice);
        }
        Button open = new Button("Open shift");
        open.getStyleClass().addAll("primary", "big-button");
        open.setMaxWidth(Double.MAX_VALUE);
        open.setOnAction(e -> Ui.attempt(() -> {
            services.shifts().openShift(Ui.parseMoney(floatField.getText(), "Opening float"));
            onShow();
        }));
        VBox form = card("Prepare your drawer", "Count the cash in the drawer before the first sale.",
                field("Opening float", floatField), quick, open);
        VBox guide = card("Ready for a good day", "One shift keeps this terminal's sales and drawer activity together.",
                note("01  Count the opening cash", "Enter the cash already in the drawer, including change."),
                note("02  Open your shift", "Checkout becomes available once the shift is open."),
                note("03  Count again at close", "The expected balance appears after you submit your count."));
        content.getChildren().add(columns(form, guide));
    }

    private void buildSummary(Shift shift) {
        ShiftSummary summary = services.shifts().currentSummary();
        FlowPane metrics = new FlowPane(14, 14,
                metric("Gross sales", Ui.money(summary.grossSalesCents()), summary.salesCount() + " completed sales"),
                metric("Opening float", Ui.money(shift.openingFloatCents()), "Cash at the start of the shift"),
                metric("Tax on sales", Ui.money(summary.taxOnSalesCents()), "Included in gross sales"));
        metrics.prefWrapLengthProperty().bind(content.widthProperty());
        metrics.getChildren().forEach(node -> ((Region) node).prefWidthProperty().bind(
                javafx.beans.binding.Bindings.max(232, metrics.widthProperty().subtract(34).divide(3))));
        content.getChildren().add(metrics);

        VBox activity = card("Payment activity", "Sales by payment type, with returns and voids below.");
        for (TenderTotal taken : summary.salesByTender()) {
            activity.getChildren().add(amountRow(taken.name(), taken.cents()));
        }
        if (summary.salesByTender().isEmpty()) activity.getChildren().add(Ui.muted("No payments taken yet."));
        Separator line = new Separator();
        activity.getChildren().addAll(line, amountRow("Refunds", summary.refundsCents()),
                amountRow("Voids", summary.voidsCents()));

        TextField counted = new TextField();
        counted.setId("shift-counted-cash");
        counted.setPromptText("0.00");
        counted.setMaxWidth(Double.MAX_VALUE);
        Button close = new Button("Count and close shift");
        close.setId("shift-close");
        close.getStyleClass().addAll("primary", "big-button");
        close.setMaxWidth(Double.MAX_VALUE);
        close.setDisable(!canClose(shift));
        close.setOnAction(e -> Ui.attempt(() -> closeShift(counted)));
        VBox drawer = card("Close your drawer", "Count the actual cash before submitting your closing balance.",
                field("Counted cash", counted),
                note("An independent count", "The expected amount is revealed after the count, so you can compare an honest total."),
                close);
        if (!canClose(shift)) {
            Label permission = Ui.muted("Only " + services.auth().usernameOf(shift.userId()) + " or a manager can close this shift.");
            permission.setWrapText(true);
            drawer.getChildren().add(permission);
        }
        content.getChildren().add(columns(activity, drawer));
    }

    private static SplitPane columns(VBox left, VBox right) {
        left.setMinWidth(300); right.setMinWidth(300);
        SplitPane split = Ui.split(.52, left, right);
        split.setMinHeight(Region.USE_PREF_SIZE);
        split.setPrefHeight(Math.max(340, Math.max(left.prefHeight(340), right.prefHeight(340)) + 16));
        return split;
    }

    private static VBox card(String title, String description, Node... children) {
        Label intro = Ui.muted(description);
        intro.setWrapText(true);
        intro.setMinHeight(Region.USE_PREF_SIZE);
        VBox box = new VBox(16, styled(title, "shift-section-title"), intro);
        box.getStyleClass().add("card");
        box.getChildren().addAll(children);
        return box;
    }

    private static VBox metric(String title, String value, String detail) {
        VBox tile = new VBox(6, Ui.muted(title), styled(value, "shift-metric-value"), Ui.muted(detail));
        tile.getStyleClass().addAll("card", "shift-metric");
        tile.setPrefWidth(232);
        return tile;
    }

    private static VBox field(String title, TextField input) {
        Label label = styled(title, "shift-field-label");
        label.setLabelFor(input);
        return new VBox(8, label, input);
    }

    private static VBox note(String title, String description) {
        Label text = Ui.muted(description);
        text.setWrapText(true);
        text.setMinHeight(Region.USE_PREF_SIZE);
        VBox note = new VBox(5, styled(title, "shift-field-label"), text);
        note.getStyleClass().add("shift-note");
        return note;
    }

    private static HBox amountRow(String name, long cents) {
        Label label = new Label(name);
        label.setWrapText(true);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Label amount = styled(Ui.money(cents), "shift-amount");
        HBox row = new HBox(12, label, gap, amount);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Label styled(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }

    private void closeShift(TextField counted) {
        long countedCents = Ui.parseMoney(counted.getText(), "Counted cash");
        if (!Ui.confirm("Close the shift with " + Ui.money(countedCents) + " counted? This cannot be undone.")) return;
        ShiftSummary summary = services.shifts().currentSummary();
        ShiftClose result = services.shifts().closeShift(countedCents);
        String cashier = services.session().requireUser().username();
        ReceiptDialog.show(ReceiptFormatter.zReport(services.storeName(), summary, result, cashier));
        onShow();
    }

    private boolean canClose(Shift shift) {
        User user = services.session().requireUser();
        return shift.userId() == user.id() || user.role().atLeast(Role.MANAGER);
    }
}
