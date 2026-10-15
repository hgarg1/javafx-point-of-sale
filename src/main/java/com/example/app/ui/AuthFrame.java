package com.example.app.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;

/** Responsive entry frame. Artwork is decorative; all form content remains native JavaFX. */
class AuthFrame extends BorderPane {
    private static final Image DAY = new Image(AuthFrame.class.getResource("artwork/retail-daylight.png").toExternalForm());
    private static final Image EVENING = new Image(AuthFrame.class.getResource("artwork/retail-evening.png").toExternalForm());
    private final StackPane artwork = new StackPane();
    private final ScrollPane formScroll;
    private final SplitPane columns;
    private boolean wideLayout = true;
    private double artworkRatio = .49;

    AuthFrame(String storeName, String eyebrow, String title, String description, VBox fields, String help) {
        getStyleClass().add("auth-shell");
        setPadding(new Insets(24));
        artwork.getStyleClass().add("auth-artwork");
        Region shade = new Region();
        shade.getStyleClass().add("auth-artwork-shade");
        Label mark = styled("P", "auth-mark");
        StackPane badge = new StackPane(mark);
        badge.getStyleClass().add("auth-mark-box");
        Label store = styled(storeName, "auth-store-name");
        store.setWrapText(true);
        store.setMinHeight(Region.USE_PREF_SIZE);
        HBox brand = new HBox(12, badge, new VBox(3, store, styled("POINT OF SALE", "auth-product-name")));
        brand.setAlignment(Pos.CENTER_LEFT);
        Region space = new Region();
        VBox.setVgrow(space, Priority.ALWAYS);
        VBox story = new VBox(18, brand, space, styled("A good day\nstarts here.", "auth-artwork-title"),
                styled("Your store. Your people.\nReady for the day ahead.", "auth-artwork-caption"),
                styled("CHECKOUT  /  INVENTORY  /  INSIGHTS", "auth-artwork-footer"));
        story.setPadding(new Insets(36));
        artwork.getChildren().addAll(shade, story);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(artwork.widthProperty());
        clip.heightProperty().bind(artwork.heightProperty());
        clip.setArcWidth(28);
        clip.setArcHeight(28);
        artwork.setClip(clip);
        getStyleClass().addListener((javafx.collections.ListChangeListener<String>) change -> updateArtwork());
        updateArtwork();

        Label heading = styled(title, "auth-form-title");
        heading.setWrapText(true);
        Label intro = styled(description, "auth-form-description");
        intro.setWrapText(true);
        Label section = styled(eyebrow, "auth-eyebrow");
        section.setWrapText(true);
        VBox form = new VBox(12, section, heading, intro, fields);
        VBox.setMargin(fields, new Insets(20, 0, 0, 0));
        form.setMaxWidth(350);
        form.getStyleClass().add("auth-form");
        Label support = styled(help, "auth-help");
        support.setWrapText(true);
        support.setMaxWidth(350);
        VBox.setMargin(support, new Insets(24, 0, 0, 0));
        VBox center = new VBox(0, form, support);
        center.setAlignment(Pos.CENTER);
        VBox.setVgrow(center, Priority.ALWAYS);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox top = new HBox(8, styled("STORE WORKSPACE", "auth-workspace"), gap,
                styled("●  On this device", "auth-local-badge"));
        top.setAlignment(Pos.CENTER_LEFT);
        Label privacy = styled("Your data stays on this device.", "auth-privacy");
        privacy.setGraphic(Icons.icon("lock"));
        privacy.setAlignment(Pos.CENTER);
        privacy.setMaxWidth(Double.MAX_VALUE);
        VBox right = new VBox(28, top, center, privacy);
        right.setAlignment(Pos.TOP_CENTER);
        right.setPadding(new Insets(24, 40, 24, 40));
        right.setMinHeight(Region.USE_PREF_SIZE);
        formScroll = new ScrollPane(right);
        formScroll.getStyleClass().add("auth-form-scroll");
        formScroll.setFitToWidth(true);
        formScroll.setFitToHeight(true);
        formScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        HBox.setHgrow(formScroll, Priority.ALWAYS);
        columns = Ui.split(artworkRatio, artwork, formScroll);
        columns.getStyleClass().add("auth-columns");
        artwork.setMinWidth(340);
        formScroll.setMinWidth(360);
        setCenter(columns);
    }

    private static Label styled(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }

    private void updateArtwork() {
        Image image = getStyleClass().contains("dark") ? EVENING : DAY;
        artwork.setBackground(new Background(new BackgroundImage(image, BackgroundRepeat.NO_REPEAT,
                BackgroundRepeat.NO_REPEAT, BackgroundPosition.CENTER,
                new BackgroundSize(100, 100, true, true, false, true))));
    }

    @Override
    protected void layoutChildren() {
        boolean wide = getWidth() >= 820;
        artwork.setVisible(wide);
        if (wide != wideLayout) {
            if (wide) {
                columns.getItems().addFirst(artwork);
                formScroll.setMinWidth(360);
                columns.setDividerPositions(artworkRatio);
            } else {
                artworkRatio = columns.getDividerPositions()[0];
                columns.getItems().remove(artwork);
                formScroll.setMinWidth(0);
            }
            wideLayout = wide;
        }
        super.layoutChildren();
    }

    static VBox field(String name, Node input) {
        Label label = styled(name, "auth-field-label");
        label.setLabelFor(input);
        return new VBox(8, label, input);
    }
}
