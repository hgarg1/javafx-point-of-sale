package com.example.app.service;

import com.example.app.domain.ProductDraft;
import com.example.app.domain.TenderCodes;
import com.example.app.service.CheckoutService.CartItem;
import com.example.app.service.CheckoutService.PaymentRequest;
import com.example.app.service.RefundService.RefundLineRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static com.example.app.service.TestServices.MANAGER;
import static com.example.app.service.TestServices.MANAGER_PASSWORD;
import static com.example.app.service.TestServices.cashierOpensShift;
import static com.example.app.service.TestServices.product;
import static com.example.app.service.TestServices.sellCash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CSV exports: readable by spreadsheets, safe from formula injection, amounts left alone. */
class ReportsCsvTest {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private AppServices app;

    @BeforeEach
    void setUp() {
        app = TestServices.fresh();
    }

    @AfterEach
    void tearDown() {
        app.close();
    }

    @Test
    void inventoryExportStartsWithAByteOrderMarkAndNeutralisesFormulaText(@TempDir Path dir) throws IOException {
        app.auth().login(MANAGER, MANAGER_PASSWORD);
        app.inventory().createProduct(new ProductDraft("EVIL", null, "=HYPERLINK(\"http://example.invalid\")",
                null, null, 100, 50, 0, 0, true), 3);
        Path file = dir.resolve("stock.csv");

        app.reports().exportInventoryValuationCsv(file);

        byte[] bytes = Files.readAllBytes(file);
        assertEquals(BOM[0], bytes[0]);
        assertEquals(BOM[1], bytes[1]);
        assertEquals(BOM[2], bytes[2]);
        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertTrue(text.contains("'=HYPERLINK"), text);
    }

    @Test
    void refundAmountsInTheLedgerStayNegativeAndUnprefixed(@TempDir Path dir) throws IOException {
        long coffee = product(app, "LED", 500, 0, 10);
        cashierOpensShift(app, 0);
        var sale = sellCash(app, coffee, 2, 0, 1_000).sale();

        app.auth().login(MANAGER, MANAGER_PASSWORD);
        long lineId = app.refunds().refundableLines(sale.id()).get(0).line().id();
        app.refunds().refund(sale.id(), List.of(new RefundLineRequest(lineId, 1)),
                TenderCodes.CASH, "returned", false, null);
        Path file = dir.resolve("ledger.csv");

        app.reports().exportLedgerCsv(LocalDate.now(), LocalDate.now(), file);

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String refundLine = lines.stream().filter(l -> l.contains("REFUND")).findFirst().orElseThrow();
        assertTrue(refundLine.contains(",-5.00,"), refundLine);
        assertFalse(refundLine.contains("'-5.00"), refundLine);
    }

    @Test
    void theLedgerCountsOnlyTheRequestedDays() {
        long coffee = product(app, "DAY", 500, 0, 10);
        cashierOpensShift(app, 0);
        sellCash(app, coffee, 1, 0, 500);

        app.auth().login(MANAGER, MANAGER_PASSWORD);
        assertEquals(0, app.reports().ledger(LocalDate.now().minusDays(10), LocalDate.now().minusDays(5)).size());
        assertEquals(1, app.reports().ledger(LocalDate.now(), LocalDate.now()).size());
    }
}
