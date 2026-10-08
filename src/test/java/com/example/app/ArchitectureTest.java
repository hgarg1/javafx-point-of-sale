package com.example.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the layers apart. Screens use services, services use repositories, and repositories use the database, so a change
 * in one layer cannot quietly reach across into another. The check reads the import lines of the source files.
 */
class ArchitectureTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "com", "example", "app");

    @Test
    void screensUseServicesAndNeverTheDatabaseOrRepositories() throws IOException {
        assertNoImports("ui", "com.example.app.repository.", "com.example.app.db.");
    }

    @Test
    void servicesDoNotDependOnScreens() throws IOException {
        assertNoImports("service", "com.example.app.ui.");
    }

    @Test
    void repositoriesDoNotDependOnServicesOrScreens() throws IOException {
        assertNoImports("repository", "com.example.app.service.", "com.example.app.ui.");
    }

    @Test
    void theDomainDependsOnNoOtherLayer() throws IOException {
        assertNoImports("domain", "com.example.app.service.", "com.example.app.ui.",
                "com.example.app.repository.", "com.example.app.db.");
    }

    private static void assertNoImports(String layer, String... forbidden) throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE.resolve(layer))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.startsWith("import ") && startsWithAny(line, forbidden)) {
                        offenders.add(layer + "/" + file.getFileName() + ":" + (i + 1) + " " + line.trim());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "The " + layer + " layer imports across a boundary:\n" + String.join("\n", offenders));
    }

    private static boolean startsWithAny(String importLine, String[] prefixes) {
        for (String prefix : prefixes) {
            if (importLine.startsWith("import " + prefix)) {
                return true;
            }
        }
        return false;
    }
}
