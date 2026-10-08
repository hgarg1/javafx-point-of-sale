package com.example.app.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Sends the app's log to a rotating file in the logs folder (five files of about 1 MB), and logs any exception that
 * escapes a thread. Call {@link #install} once at startup, before the first database call.
 */
public final class AppLog {

    private static final Logger LOG = Logger.getLogger(AppLog.class.getName());

    private AppLog() {
    }

    public static void install(Path logFolder) throws IOException {
        Files.createDirectories(logFolder);
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$-7s %3$s - %5$s%6$s%n");

        FileHandler file = new FileHandler(logFolder.resolve("pos-%g.log").toString(), 1_000_000, 5, true);
        file.setFormatter(new SimpleFormatter());
        file.setLevel(Level.INFO);

        Logger root = Logger.getLogger("");
        root.addHandler(file);
        for (Handler handler : root.getHandlers()) {
            if (handler != file) {
                handler.setLevel(Level.WARNING); // keep the console quiet; the file has the detail
            }
        }
        root.setLevel(Level.INFO);

        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
                LOG.log(Level.SEVERE, "Unexpected error on thread " + thread.getName(), error));
    }

    /** The path of the file the log writes to, for the error dialog. */
    public static String describeLocation(Path logFolder) {
        return logFolder.toAbsolutePath().toString();
    }
}
