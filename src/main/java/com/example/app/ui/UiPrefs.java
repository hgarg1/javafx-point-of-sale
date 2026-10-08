package com.example.app.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Settings that belong to this computer rather than to the store: colour scheme, density, idle lock and the window's
 * position. Kept in {@code ui.properties} in the app's folder, so they survive restarts. Reading or writing it never
 * stops the app; a damaged file just means the defaults are used.
 */
public final class UiPrefs {

    private static final Logger LOG = Logger.getLogger(UiPrefs.class.getName());
    static final int DEFAULT_IDLE_MINUTES = 10;

    /** Null for {@link #defaults()}, which never reads or writes a file. */
    private final Path file;
    private final Properties values = new Properties();

    private UiPrefs(Path file) {
        this.file = file;
    }

    /** Default settings, not stored anywhere. Used by tests and by any caller without a folder. */
    public static UiPrefs defaults() {
        return new UiPrefs(null);
    }

    public static UiPrefs load(Path folder) {
        UiPrefs prefs = new UiPrefs(folder.resolve("ui.properties"));
        if (Files.exists(prefs.file)) {
            try (InputStream in = Files.newInputStream(prefs.file)) {
                prefs.values.load(in);
            } catch (IOException | IllegalArgumentException e) {
                LOG.log(Level.WARNING, "Ignoring unreadable " + prefs.file, e);
            }
        }
        return prefs;
    }

    public ThemeManager.Mode themeMode() {
        return parseEnum(values.getProperty("theme"), ThemeManager.Mode.class, ThemeManager.Mode.SYSTEM);
    }

    public void setThemeMode(ThemeManager.Mode mode) {
        values.setProperty("theme", mode.name().toLowerCase(Locale.ROOT));
    }

    public ThemeManager.Density density() {
        return parseEnum(values.getProperty("density"), ThemeManager.Density.class, ThemeManager.Density.COMFORTABLE);
    }

    public void setDensity(ThemeManager.Density density) {
        values.setProperty("density", density.name().toLowerCase(Locale.ROOT));
    }

    /** Minutes without input before the till locks. Zero turns the lock off. */
    public Duration idleLimit() {
        try {
            int minutes = Integer.parseInt(values.getProperty("idle_minutes", String.valueOf(DEFAULT_IDLE_MINUTES)).trim());
            return Duration.ofMinutes(Math.max(0, Math.min(minutes, 240)));
        } catch (NumberFormatException e) {
            return Duration.ofMinutes(DEFAULT_IDLE_MINUTES);
        }
    }

    public void setIdleMinutes(int minutes) {
        values.setProperty("idle_minutes", String.valueOf(Math.max(0, Math.min(minutes, 240))));
    }

    /** The window's bounds from last time, if they were saved. */
    public Optional<double[]> window() {
        try {
            double x = Double.parseDouble(values.getProperty("window_x"));
            double y = Double.parseDouble(values.getProperty("window_y"));
            double w = Double.parseDouble(values.getProperty("window_width"));
            double h = Double.parseDouble(values.getProperty("window_height"));
            if (w < 800 || h < 600) {
                return Optional.empty();
            }
            return Optional.of(new double[]{x, y, w, h});
        } catch (NumberFormatException | NullPointerException e) {
            return Optional.empty();
        }
    }

    public void saveWindow(double x, double y, double width, double height) {
        values.setProperty("window_x", String.valueOf(x));
        values.setProperty("window_y", String.valueOf(y));
        values.setProperty("window_width", String.valueOf(width));
        values.setProperty("window_height", String.valueOf(height));
    }

    public void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                values.store(out, "Point of Sale: settings for this computer");
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not save " + file, e);
        }
    }

    private static <E extends Enum<E>> E parseEnum(String text, Class<E> type, E fallback) {
        if (text == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
