package com.example.app.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/** Writes RFC 4180 style CSV with quoting for commas, quotes and line breaks. */
public final class CsvWriter {

    private CsvWriter() {
    }

    public static void write(Path file, List<String> header, List<List<String>> rows) {
        StringBuilder out = new StringBuilder();
        appendRow(out, header);
        for (List<String> row : rows) {
            appendRow(out, row);
        }
        try {
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file, e);
        }
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        return needsQuotes ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    private static void appendRow(StringBuilder out, List<String> cells) {
        out.append(cells.stream().map(CsvWriter::escape).collect(Collectors.joining(","))).append("\r\n");
    }
}
