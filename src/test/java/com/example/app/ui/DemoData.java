package com.example.app.ui;

import com.example.app.service.AppServices;

/** Screenshot fixtures use the same data as the explicitly launched demo. */
final class DemoData {
    private DemoData() { }

    static void seed(AppServices app) {
        com.example.app.service.DemoData.seed(app);
    }
}
