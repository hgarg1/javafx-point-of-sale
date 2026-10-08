package com.example.app.ui;

import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Applies the stylesheet and the light or dark mode to every window and dialog. Dialogs and alerts are separate
 * scenes, so each one has to be registered here as well as the main window.
 */
public final class ThemeManager {

    /** Which colour scheme to use. SYSTEM follows the operating system and changes when it does. */
    public enum Mode { SYSTEM, LIGHT, DARK }

    /** Density sets control sizes. Comfortable is the default; touch suits a touchscreen till. */
    public enum Density { COMPACT, COMFORTABLE, TOUCH }

    private static final String STYLESHEET = ThemeManager.class.getResource("theme.css").toExternalForm();

    private static final List<WeakReference<Parent>> ROOTS = new ArrayList<>();
    private static Mode mode = Mode.SYSTEM;
    private static Density density = Density.COMFORTABLE;
    private static boolean listening;

    private ThemeManager() {
    }

    public static void setMode(Mode newMode) {
        mode = newMode;
        refreshAll();
    }

    public static Mode mode() {
        return mode;
    }

    public static void setDensity(Density newDensity) {
        density = newDensity;
        refreshAll();
    }

    public static boolean isDark() {
        return switch (mode) {
            case LIGHT -> false;
            case DARK -> true;
            case SYSTEM -> systemIsDark();
        };
    }

    /** A scene with the theme applied. Use this for every window. */
    public static Scene newScene(Parent root, double width, double height) {
        apply(root);
        return new Scene(root, width, height);
    }

    /** Applies the stylesheet, colour scheme and density to a window root or a dialog pane. */
    public static void apply(Parent root) {
        if (!root.getStylesheets().contains(STYLESHEET)) {
            root.getStylesheets().add(STYLESHEET);
        }
        ROOTS.add(new WeakReference<>(root));
        listenToSystemChanges();
        refresh(root);
    }

    private static void refreshAll() {
        Iterator<WeakReference<Parent>> it = ROOTS.iterator();
        while (it.hasNext()) {
            Parent root = it.next().get();
            if (root == null) {
                it.remove();
            } else {
                refresh(root);
            }
        }
    }

    private static void refresh(Parent root) {
        root.getStyleClass().removeAll("dark", "compact", "touch");
        if (isDark()) {
            root.getStyleClass().add("dark");
        }
        switch (density) {
            case COMPACT -> root.getStyleClass().add("compact");
            case TOUCH -> root.getStyleClass().add("touch");
            default -> {
                // comfortable is the stylesheet's default; no class needed
            }
        }
    }

    private static boolean systemIsDark() {
        try {
            return Platform.getPreferences().colorSchemeProperty().get() == ColorScheme.DARK;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** When the operating system switches between light and dark, follow it if the setting is SYSTEM. */
    private static void listenToSystemChanges() {
        if (listening) {
            return;
        }
        listening = true;
        try {
            Platform.getPreferences().colorSchemeProperty().addListener((obs, was, now) -> {
                if (mode == Mode.SYSTEM) {
                    refreshAll();
                }
            });
        } catch (RuntimeException e) {
            // The platform does not report a colour scheme; the light theme is used.
        }
    }
}
