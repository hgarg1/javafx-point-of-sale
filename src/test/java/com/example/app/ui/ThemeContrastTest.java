package com.example.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every piece of text in the theme must stay readable (WCAG AA, 4.5:1), in light and dark modes. */
class ThemeContrastTest {

    private static final double MINIMUM_RATIO = 4.5;
    private static final Pattern TOKEN = Pattern.compile("(-color-[a-z-]+):\\s*(#[0-9a-fA-F]{6});");
    private static final Pattern ROOT = Pattern.compile("\\.root\\s*\\{([^}]*)\\}");
    private static final Pattern DARK_ROOT = Pattern.compile("\\.root\\.dark\\s*\\{([^}]*)\\}");

    /** Text token paired with the background token it is shown on. */
    private static final String[][] PAIRS = {
            {"-color-text", "-color-surface"},
            {"-color-text", "-color-surface-alt"},
            {"-color-text-muted", "-color-surface"},
            {"-color-text-muted", "-color-surface-alt"},
            {"-color-text", "-color-selected"},
            {"-color-accent-text", "-color-accent"},
            {"-color-danger", "-color-surface"},
            {"-color-danger-text", "-color-danger"},
            {"-color-success-text", "-color-success"},
    };

    @Test
    void lightTheme() throws IOException {
        Map<String, String> tokens = tokens(ROOT);
        assertReadable("light", tokens);
    }

    @Test
    void darkTheme() throws IOException {
        Map<String, String> tokens = tokens(ROOT);
        tokens.putAll(tokens(DARK_ROOT));
        assertReadable("dark", tokens);
    }

    private static void assertReadable(String mode, Map<String, String> tokens) {
        for (String[] pair : PAIRS) {
            double ratio = contrast(tokens.get(pair[0]), tokens.get(pair[1]));
            assertTrue(ratio >= MINIMUM_RATIO,
                    mode + ": " + pair[0] + " on " + pair[1] + " is " + String.format("%.2f", ratio) + ":1");
        }
    }

    private static Map<String, String> tokens(Pattern block) throws IOException {
        String css = read("theme.css");
        Matcher block1 = block.matcher(css);
        assertTrue(block1.find(), "missing block " + block.pattern());
        Map<String, String> tokens = new HashMap<>();
        Matcher t = TOKEN.matcher(block1.group(1));
        while (t.find()) {
            tokens.put(t.group(1), t.group(2));
        }
        return tokens;
    }

    private static String read(String name) throws IOException {
        try (InputStream in = ThemeManager.class.getResourceAsStream(name)) {
            assertTrue(in != null, "theme.css is missing from the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** WCAG 2.x contrast ratio between two #rrggbb colours. */
    static double contrast(String first, String second) {
        double a = luminance(first);
        double b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        return 0.2126 * channel((rgb >> 16) & 0xFF) + 0.7152 * channel((rgb >> 8) & 0xFF) + 0.0722 * channel(rgb & 0xFF);
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
