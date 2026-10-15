package com.example.app;

import com.example.app.service.AppServices;
import com.example.app.service.DemoData;
import com.example.app.util.PasswordHasher;

/** Plain Java entry point for native, classpath-based launchers. */
public final class Launcher {
    private Launcher() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--packaging-smoke".equals(args[0])) {
            // In-memory only: packaging checks must never create or modify a user's store.
            Class.forName("javafx.scene.control.DatePicker", false, Launcher.class.getClassLoader());
            for (String resource : new String[]{"/com/example/app/ui/theme.css",
                    "/com/example/app/ui/artwork/shift-panorama.png", "/com/example/app/db/V1__init.sql"}) {
                if (Launcher.class.getResource(resource) == null) throw new IllegalStateException("Missing " + resource);
            }
            try (AppServices app = new AppServices("jdbc:h2:mem:packaging" + java.util.UUID.randomUUID(), new PasswordHasher(1000))) {
                if (!app.auth().needsSetup()) throw new IllegalStateException("Normal store was pre-seeded");
                if (Boolean.getBoolean("pos.demo")) {
                    DemoData.seed(app);
                    app.auth().login(DemoData.OWNER, DemoData.OWNER_PASSWORD);
                }
            }
            System.out.println("Packaging smoke passed: " + (Boolean.getBoolean("pos.demo") ? "demo" : "app"));
            return;
        }
        App.main(args);
    }
}
