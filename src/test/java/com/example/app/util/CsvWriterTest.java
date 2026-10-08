package com.example.app.util;

import com.example.app.util.CsvWriter.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvWriterTest {

    @Test
    void quotesCellsThatContainSeparatorsQuotesOrLineBreaks() {
        assertEquals("plain", CsvWriter.escape("plain"));
        assertEquals("\"a,b\"", CsvWriter.escape("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", CsvWriter.escape("say \"hi\""));
        assertEquals("\"line1\nline2\"", CsvWriter.escape("line1\nline2"));
        assertEquals("", CsvWriter.escape(null));
    }

    @Test
    void textCellsThatStartWithAFormulaCharacterGetAnApostrophe() {
        assertEquals("'=SUM(A1)", CsvWriter.cell(new Text("=SUM(A1)")));
        assertEquals("'+1", CsvWriter.cell(new Text("+1")));
        assertEquals("'-5", CsvWriter.cell(new Text("-5")));
        assertEquals("'@cmd", CsvWriter.cell(new Text("@cmd")));
        assertEquals("plain text", CsvWriter.cell(new Text("plain text")));
    }

    @Test
    void plainValuesAreNeverGuardedSoNegativeAmountsStayNumbers() {
        assertEquals("-5.00", CsvWriter.cell("-5.00"));
        assertEquals("", CsvWriter.cell(null));
    }

    @Test
    void theFileStartsWithAByteOrderMarkSoSpreadsheetsReadUtf8(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("out.csv");
        CsvWriter.write(file, List.of("name", "amount"), List.of(List.of(new Text("Café"), "1.00")));

        byte[] bytes = Files.readAllBytes(file);
        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals((byte) 0xBB, bytes[1]);
        assertEquals((byte) 0xBF, bytes[2]);
        assertTrue(new String(bytes, 3, bytes.length - 3, java.nio.charset.StandardCharsets.UTF_8)
                .contains("Café,1.00"));
    }
}
