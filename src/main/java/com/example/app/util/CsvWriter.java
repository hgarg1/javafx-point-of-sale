package com.example.app.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Writes RFC 4180 CSV for spreadsheets.
 *
 * <ul>
 *   <li>The file starts with a UTF-8 byte-order mark, so Excel shows accented names correctly.</li>
 *   <li>Cells wrapped in {@link Text} are protected against spreadsheet formula injection: a value that starts with
 *       {@code = + - @} (or a tab or carriage return) gets a leading apostrophe, so it is shown as text and not run as
 *       a formula. Only text cells are guarded. Amounts are not, because a refund legitimately starts with a minus.</li>
 * </ul>
 */
public final class CsvWriter {

    /** A cell that holds user-entered text (names, references, reasons). */
    public record Text(String value) {
    }

    private static final String FORMULA_STARTS = "=+-@\t\r";

    private CsvWriter() {
    }

    /** Rows hold {@link Text} cells or plain values. Plain values are written as they are. */
    public static void write(Path file, List<String> header, List<List<?>> rows) {
        StringBuilder out = new StringBuilder();
        out.append('﻿');
        appendRow(out, header.stream().map(h -> (Object) h).toList());
        for (List<?> row : rows) {
            appendRow(out, row);
        }
        try {
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file, e);
        }
    }

    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Text text) {
            return escape(guardFormula(text.value() == null ? "" : text.value()));
        }
        return escape(value.toString());
    }

    static String guardFormula(String value) {
        return !value.isEmpty() && FORMULA_STARTS.indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        return needsQuotes ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    private static void appendRow(StringBuilder out, List<?> cells) {
        out.append(cells.stream().map(CsvWriter::cell).collect(Collectors.joining(","))).append("\r\n");
    }
}
