package com.example.app.ui;

import javafx.scene.shape.SVGPath;

import java.util.Map;

/**
 * Line icons from Lucide 1.47.0 (ISC licence; see THIRD-PARTY-NOTICES.md), drawn as stroked paths so they follow the
 * theme's text colour. Each entry is the icon's 24-unit path data with circles, rectangles and polylines converted
 * to path commands.
 */
final class Icons {

    private static final Map<String, String> PATHS = Map.ofEntries(
            Map.entry("cart", "M2.05 2.05l1.099-.028a1 1 0 0 1 1.008.815l2.69 14.347A1 1 0 0 0 7.83 18H18"
                    + " M4.563 5h16.435a1 1 0 0 1 .981 1.204l-1.026 6.226A2 2 0 0 1 18.962 14H6.25"
                    + " M16 20a2 2 0 1 0 4 0a2 2 0 1 0-4 0 M6 20a2 2 0 1 0 4 0a2 2 0 1 0-4 0"),
            Map.entry("clock", "M22 12a10 10 0 1 0-20 0a10 10 0 1 0 20 0 M12 6v6l4 2"),
            Map.entry("refund", "M9 14L4 9l5-5 M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11"),
            Map.entry("products", "M11 21.73a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73z"
                    + " M12 22V12 M3.29 7L12 12L20.71 7 M7.5 4.27l9 5.15"),
            Map.entry("stock", "M2.97 12.92A2 2 0 0 0 2 14.63v3.24a2 2 0 0 0 .97 1.71l3 1.8a2 2 0 0 0 2.06 0L12 19v-5.5l-5-3-4.03 2.42Z"
                    + " M7 16.5l-4.74-2.85 M7 16.5l5-3 M7 16.5v5.17"
                    + " M12 13.5V19l3.97 2.38a2 2 0 0 0 2.06 0l3-1.8a2 2 0 0 0 .97-1.71v-3.24a2 2 0 0 0-.97-1.71L17 10.5l-5 3Z"
                    + " M17 16.5l-5-3 M17 16.5l4.74-2.85 M17 16.5v5.17"
                    + " M7.97 4.42A2 2 0 0 0 7 6.13v4.37l5 3 5-3V6.13a2 2 0 0 0-.97-1.71l-3-1.8a2 2 0 0 0-2.06 0l-3 1.8Z"
                    + " M12 8L7.26 5.15 M12 8l4.74-2.85 M12 13.5V8"),
            Map.entry("reports", "M3 3v16a2 2 0 0 0 2 2h16 M18 17V9 M13 17V5 M8 17v-3"),
            Map.entry("truck", "M14 18V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v11a1 1 0 0 0 1 1h2 M15 18H9"
                    + " M19 18h2a1 1 0 0 0 1-1v-3.65a1 1 0 0 0-.22-.624l-3.48-4.35A1 1 0 0 0 17.52 8H14"
                    + " M15 18a2 2 0 1 0 4 0a2 2 0 1 0-4 0 M5 18a2 2 0 1 0 4 0a2 2 0 1 0-4 0"),
            Map.entry("users", "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M16 3.128a4 4 0 0 1 0 7.744"
                    + " M22 21v-2a4 4 0 0 0-3-3.87 M5 7a4 4 0 1 0 8 0a4 4 0 1 0-8 0"),
            Map.entry("logout", "M16 17l5-5-5-5 M21 12H9 M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"),
            Map.entry("lock", "M5 11H19Q21 11 21 13V20Q21 22 19 22H5Q3 22 3 20V13Q3 11 5 11Z M7 11V7a5 5 0 0 1 10 0v4"),
            Map.entry("search", "M21 21l-4.34-4.34 M3 11a8 8 0 1 0 16 0a8 8 0 1 0-16 0"),
            Map.entry("scan", "M3 7V5a2 2 0 0 1 2-2h2 M17 3h2a2 2 0 0 1 2 2v2 M21 17v2a2 2 0 0 1-2 2h-2"
                    + " M7 21H5a2 2 0 0 1-2-2v-2 M8 7v10 M12 7v10 M17 7v10"),
            Map.entry("sun", "M8 12a4 4 0 1 0 8 0a4 4 0 1 0-8 0 M12 2v2 M12 20v2 M4.93 4.93l1.41 1.41"
                    + " M17.66 17.66l1.41 1.41 M2 12h2 M20 12h2 M6.34 17.66l-1.41 1.41 M19.07 4.93l-1.41 1.41"),
            Map.entry("moon", "M20.985 12.486a9 9 0 1 1-9.473-9.472c.405-.022.617.46.402.803a6 6 0 0 0 8.268 8.268c.344-.215.825-.004.803.401"),
            Map.entry("settings", "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915"
                    + " M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0"),
            Map.entry("plus", "M5 12h14 M12 5v14"),
            Map.entry("minus", "M5 12h14"),
            Map.entry("trash", "M10 11v6 M14 11v6 M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6 M3 6h18 M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"));

    private Icons() {
    }

    /** A 24-unit icon with the "icon" style class, so its colour comes from the theme. */
    static SVGPath icon(String name) {
        String data = PATHS.get(name);
        if (data == null) {
            throw new IllegalArgumentException("No icon named " + name);
        }
        SVGPath path = new SVGPath();
        path.setContent(data);
        path.getStyleClass().add("icon");
        return path;
    }

    static boolean has(String name) {
        return PATHS.containsKey(name);
    }
}
