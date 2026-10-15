package com.example.app;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LauncherTest {
    @Test
    void smokeChecksBothVariantsWithoutWritingStoreData() throws Exception {
        String previous = System.getProperty("pos.demo");
        try {
            System.clearProperty("pos.demo");
            Launcher.main(new String[]{"--packaging-smoke"});
            System.setProperty("pos.demo", "true");
            Launcher.main(new String[]{"--packaging-smoke"});
            assertNotEquals(App.homeFolder(false), App.homeFolder(true));
        } finally {
            if (previous == null) System.clearProperty("pos.demo"); else System.setProperty("pos.demo", previous);
        }
    }
}
