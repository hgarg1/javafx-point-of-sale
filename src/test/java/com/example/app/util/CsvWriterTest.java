package com.example.app.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvWriterTest {

    @Test
    void quotesCellsThatContainSeparatorsQuotesOrLineBreaks() {
        assertEquals("plain", CsvWriter.escape("plain"));
        assertEquals("\"a,b\"", CsvWriter.escape("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", CsvWriter.escape("say \"hi\""));
        assertEquals("\"line1\nline2\"", CsvWriter.escape("line1\nline2"));
        assertEquals("", CsvWriter.escape(null));
    }
}
