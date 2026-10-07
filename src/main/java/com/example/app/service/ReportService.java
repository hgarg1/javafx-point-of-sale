package com.example.app.service;

import com.example.app.domain.Money;
import com.example.app.domain.Product;
import com.example.app.domain.Refund;
import com.example.app.domain.Role;
import com.example.app.domain.Sale;
import com.example.app.error.BusinessException;
import com.example.app.repository.ProductRepository;
import com.example.app.repository.RefundRepository;
import com.example.app.repository.SaleRepository;
import com.example.app.util.CsvWriter;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ReportService {

    /** One sale (positive) or refund (negative) in the ledger, with the running total after it. */
    public record LedgerEntry(Instant at, String type, String reference, long amountCents, long runningTotalCents) {
    }

    private final SaleRepository sales;
    private final RefundRepository refunds;
    private final ProductRepository products;
    private final Session session;

    ReportService(SaleRepository sales, RefundRepository refunds, ProductRepository products, Session session) {
        this.sales = sales;
        this.refunds = refunds;
        this.products = products;
        this.session = session;
    }

    /** Sales and refunds between two dates, inclusive, in the computer's local time zone. */
    public List<LedgerEntry> ledger(LocalDate from, LocalDate to) {
        session.requireAtLeast(Role.MANAGER);
        if (to.isBefore(from)) {
            throw new BusinessException("The end date is before the start date");
        }
        ZoneId zone = ZoneId.systemDefault();
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();

        List<LedgerEntry> raw = new ArrayList<>();
        for (Sale sale : sales.findBetween(start, end)) {
            raw.add(new LedgerEntry(sale.createdAt(), "SALE", sale.receiptNo(), sale.totalCents(), 0));
        }
        for (Refund refund : refunds.findBetween(start, end)) {
            raw.add(new LedgerEntry(refund.createdAt(), "REFUND", "Refund #" + refund.id() + " on sale #" + refund.saleId(),
                    -refund.amountCents(), 0));
        }
        raw.sort(Comparator.comparing(LedgerEntry::at));

        List<LedgerEntry> result = new ArrayList<>();
        long running = 0;
        for (LedgerEntry e : raw) {
            running += e.amountCents();
            result.add(new LedgerEntry(e.at(), e.type(), e.reference(), e.amountCents(), running));
        }
        return result;
    }

    public void exportLedgerCsv(LocalDate from, LocalDate to, Path file) {
        List<List<String>> rows = new ArrayList<>();
        ZoneId zone = ZoneId.systemDefault();
        for (LedgerEntry e : ledger(from, to)) {
            rows.add(List.of(
                    e.at().atZone(zone).toLocalDateTime().toString(),
                    e.type(),
                    e.reference(),
                    Money.toPlainAmount(e.amountCents()),
                    Money.toPlainAmount(e.runningTotalCents())));
        }
        CsvWriter.write(file, List.of("when", "type", "reference", "amount", "running_total"), rows);
    }

    /** Stock on hand valued at cost, for every product. */
    public void exportInventoryValuationCsv(Path file) {
        session.requireAtLeast(Role.MANAGER);
        List<List<String>> rows = new ArrayList<>();
        long totalValue = 0;
        for (Product p : products.findAll()) {
            long value = p.stockQty() * p.costCents();
            totalValue += value;
            rows.add(List.of(
                    p.sku(),
                    p.name(),
                    String.valueOf(p.stockQty()),
                    Money.toPlainAmount(p.costCents()),
                    Money.toPlainAmount(value)));
        }
        rows.add(List.of("", "TOTAL", "", "", Money.toPlainAmount(totalValue)));
        CsvWriter.write(file, List.of("sku", "name", "qty_on_hand", "unit_cost", "value_at_cost"), rows);
    }
}
