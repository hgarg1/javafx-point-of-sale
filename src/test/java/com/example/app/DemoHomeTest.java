package com.example.app;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DemoHomeTest {
    @Test
    void demoAndNormalDataFoldersAreSeparate() {
        assertEquals(App.homeFolder(), App.homeFolder(false));
        assertNotEquals(App.homeFolder(), App.homeFolder(true));
        assertEquals(".cashier-app", App.homeFolder().getFileName().toString());
        assertEquals(".cashier-app-demo", App.homeFolder(true).getFileName().toString());
    }
}
