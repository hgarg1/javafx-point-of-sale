package com.example.app.service;

import com.example.app.domain.Money;
import com.example.app.domain.Product;
import com.example.app.domain.Refund;
import com.example.app.domain.RefundKind;
import com.example.app.domain.Role;
import com.example.app.domain.Sale;
import com.example.app.domain.TenderKind;
import com.example.app.domain.TenderTotal;
import com.example.app.domain.User;
import com.example.app.error.BusinessException;
import com.example.app.repository.ProductRepository;
import com.example.app.repository.RefundRepository;
import com.example.app.repository.SaleRepository;
import com.example.app.repository.TenderRepository;
import com.example.app.repository.UserRepository;
import com.example.app.util.CsvWriter;
import com.example.app.util.CsvWriter.Text;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class ReportService {

    /** One sale (positive) or refund (negative) in the ledger, with the running total after it. */
    public record LedgerEntry(Instant at, String type, String reference, long amountCents, long runningTotalCents) {
    }

    /** A sale for the sales history list, with its cashier's name and the amount refunded so far. */
    public record SaleSummary(Sale sale, String cashier, long refundedCents) {
    }

    /** Net takings for one local day. */
    public record DailyTotal(LocalDate day, long netCents) {
    }

    /**
     * Headline figures for a date range. Voids are counted apart from refunds. Cash and card are the takings on the
     * tenders of those kinds, whatever the tenders are called.
     */
    public record Dashboard(long grossSalesCents, long refundsCents, long voidsCents, long netSalesCents, int transactions,
                            long averageBasketCents, long cashCents, long cardCents, int lowStockCount,
                            List<DailyTotal> daily) {
    }

    private final SaleRepository sales;
    private final RefundRepository refunds;
    private final ProductRepository products;
    private final UserRepository users;
    private final TenderRepository tenders;
    private final Session session;

    ReportService(SaleRepository sales, RefundRepository refunds, ProductRepository products, UserRepository users,
                  TenderRepository tenders, Session session) {
        this.sales = sales;
        this.refunds = refunds;
        this.products = products;
        this.users = users;
        this.tenders = tenders;
        this.session = session;
    }

    /** Sales between two dates (inclusive), newest first. */
    public List<SaleSummary> salesBetween(LocalDate from, LocalDate to) {
        session.requireAtLeast(Role.MANAGER);
        Range range = Range.of(from, to);
        List<SaleSummary> result = new ArrayList<>();
        for (Sale sale : sales.findBetween(range.start(), range.end())) {
            long refunded = refunds.refundedByTenderForSale(sale.id()).values().stream().mapToLong(Long::longValue).sum();
            String cashier = users.findById(sale.userId()).map(User::username).orElse("(removed user)");
            result.add(new SaleSummary(sale, cashier, refunded));
        }
        result.sort(Comparator.comparing((SaleSummary s) -> s.sale().createdAt()).reversed());
        return result;
    }

    /** Takings, refunds, voids, tender mix and the day-by-day net sales for a date range. */
    public Dashboard dashboard(LocalDate from, LocalDate to) {
        session.requireAtLeast(Role.MANAGER);
        Range range = Range.of(from, to);
        ZoneId zone = ZoneId.systemDefault();
        Map<String, TenderKind> kinds = tenders.kinds();

        List<Sale> saleList = sales.findBetween(range.start(), range.end());
        long gross = 0;
        long cash = 0;
        long card = 0;
        Map<LocalDate, Long> byDay = new TreeMap<>();
        for (Sale sale : saleList) {
            gross += sale.totalCents();
            LocalDate day = sale.createdAt().atZone(zone).toLocalDate();
            byDay.merge(day, sale.totalCents(), Long::sum);
            for (TenderTotal paid : sales.appliedByTenderForSale(sale.id())) {
                TenderKind kind = kinds.get(paid.tender());
                if (kind == TenderKind.CASH) {
                    cash += paid.cents();
                } else if (kind == TenderKind.CARD) {
                    card += paid.cents();
                }
            }
        }
        long refundTotal = 0;
        long voidTotal = 0;
        for (Refund refund : refunds.findBetween(range.start(), range.end())) {
            if (refund.kind() == RefundKind.VOID) {
                voidTotal += refund.amountCents();
            } else {
                refundTotal += refund.amountCents();
            }
            LocalDate day = refund.createdAt().atZone(zone).toLocalDate();
            byDay.merge(day, -refund.amountCents(), Long::sum);
        }
        List<DailyTotal> daily = byDay.entrySet().stream().map(e -> new DailyTotal(e.getKey(), e.getValue())).toList();
        int transactions = saleList.size();
        long average = transactions == 0 ? 0 : gross / transactions;
        int lowStock = products.findLowStock().size();
        return new Dashboard(gross, refundTotal, voidTotal, gross - refundTotal - voidTotal, transactions, average,
                cash, card, lowStock, daily);
    }

    /** The start instant (inclusive) and end instant (exclusive) of a range of local dates. */
    private record Range(Instant start, Instant end) {
        static Range of(LocalDate from, LocalDate to) {
            if (to.isBefore(from)) {
                throw new BusinessException("The end date is before the start date");
            }
            ZoneId zone = ZoneId.systemDefault();
            return new Range(from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant());
        }
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
            raw.add(new LedgerEntry(refund.createdAt(),
                    refund.kind().name(), "Refund #" + refund.id() + " on sale #" + refund.saleId(),
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
        List<List<?>> rows = new ArrayList<>();
        ZoneId zone = ZoneId.systemDefault();
        for (LedgerEntry e : ledger(from, to)) {
            rows.add(List.of(
                    new Text(e.at().atZone(zone).toLocalDateTime().toString()),
                    new Text(e.type()),
                    new Text(e.reference()),
                    Money.toPlainAmount(e.amountCents()),
                    Money.toPlainAmount(e.runningTotalCents())));
        }
        CsvWriter.write(file, List.of("when", "type", "reference", "amount", "running_total"), rows);
    }

    /** Stock on hand valued at cost, for every product. */
    public void exportInventoryValuationCsv(Path file) {
        session.requireAtLeast(Role.MANAGER);
        List<List<?>> rows = new ArrayList<>();
        long totalValue = 0;
        for (Product p : products.findAll()) {
            long value = p.stockQty() * p.costCents();
            totalValue += value;
            rows.add(List.of(
                    new Text(p.sku()),
                    new Text(p.name()),
                    String.valueOf(p.stockQty()),
                    Money.toPlainAmount(p.costCents()),
                    Money.toPlainAmount(value)));
        }
        rows.add(List.of(new Text(""), new Text("TOTAL"), "", "", Money.toPlainAmount(totalValue)));
        CsvWriter.write(file, List.of("sku", "name", "qty_on_hand", "unit_cost", "value_at_cost"), rows);
    }
}
