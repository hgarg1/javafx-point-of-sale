package com.example.app.ui;

/**
 * A main-area screen. The shell keeps screens between visits, so each one reloads its data when it is shown again;
 * that is what keeps a cached screen from showing stale prices or stock.
 */
interface Screen {

    void onShow();
}
