package com.example.app.ui;

import com.example.app.domain.Money;
import com.example.app.domain.Payment;
import com.example.app.domain.TenderTotal;
import com.example.app.domain.Sale;
import com.example.app.domain.SaleLine;
import com.example.app.service.CheckoutService.SaleDetails;
import com.example.app.service.ShiftService;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Plain-text receipt, 40 columns wide, suitable for a text file or a plain printer. */
final class ReceiptFormatter {

    private static final int WIDTH = 40;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private ReceiptFormatter() {
    }

    static String format(String storeName, String footer, SaleDetails details, String cashierName) {
        Sale sale = details.sale();
        StringBuilder out = new StringBuilder();
        out.append(center(storeName)).append('\n');
        out.append("Receipt ").append(sale.receiptNo()).append('\n');
        out.append(WHEN.format(sale.createdAt().atZone(ZoneId.systemDefault()))).append('\n');
        out.append("Cashier: ").append(cashierName).append('\n');
        out.append(rule()).append('\n');

        for (SaleLine line : details.lines()) {
            out.append(row(line.nameSnapshot(), Money.toPlainAmount(line.lineTotalCents()))).append('\n');
            out.append("  ").append(line.qty()).append(" x ").append(Money.toPlainAmount(line.unitPriceCents()));
            if (line.discountCents() > 0) {
                out.append("  (discount -").append(Money.toPlainAmount(line.discountCents())).append(')');
            }
            out.append('\n');
        }
        out.append(rule()).append('\n');

        out.append(row("Subtotal", Money.toPlainAmount(sale.subtotalCents()))).append('\n');
        if (sale.discountCents() > 0) {
            out.append(row("Discount", "-" + Money.toPlainAmount(sale.discountCents()))).append('\n');
        }
        out.append(row("Tax", Money.toPlainAmount(sale.taxCents()))).append('\n');
        out.append(row("TOTAL", Money.toPlainAmount(sale.totalCents()))).append('\n');
        out.append(rule()).append('\n');

        for (Payment payment : details.payments()) {
            String label = payment.tenderName();
            out.append(row(label, Money.toPlainAmount(payment.appliedCents()))).append('\n');
            if (payment.changeCents() > 0) {
                out.append(row("Change", Money.toPlainAmount(payment.changeCents()))).append('\n');
            }
        }
        out.append(rule()).append('\n');
        if (!details.giftCardCodes().isEmpty()) {
            out.append(rule()).append('\n');
            out.append(center("Gift card codes")).append('\n');
            for (String code : details.giftCardCodes()) {
                out.append(center(code)).append('\n');
            }
        }
        if (footer != null && !footer.isBlank()) {
            out.append(center(footer)).append('\n');
        }
        return out.toString();
    }

    /** End-of-shift report: what the drawer should hold, what was counted, and the difference. */
    static String zReport(String storeName, ShiftService.ShiftSummary summary, ShiftService.ShiftClose close,
                          String cashierName) {
        StringBuilder out = new StringBuilder();
        out.append(center(storeName)).append('\n');
        out.append(center("End of shift")).append('\n');
        out.append(WHEN.format(java.time.Instant.now().atZone(ZoneId.systemDefault()))).append('\n');
        out.append("Closed by: ").append(cashierName).append('\n');
        out.append(rule()).append('\n');
        out.append(row("Opening float", Money.toPlainAmount(summary.shift().openingFloatCents()))).append('\n');
        out.append(row("Sales (" + summary.salesCount() + ")", Money.toPlainAmount(summary.grossSalesCents()))).append('\n');
        out.append(row("Tax on sales", Money.toPlainAmount(summary.taxOnSalesCents()))).append('\n');
        for (TenderTotal taken : summary.salesByTender()) {
            out.append(row(taken.name() + " taken", Money.toPlainAmount(taken.cents()))).append('\n');
        }
        out.append(row("Refunds", "-" + Money.toPlainAmount(summary.refundsCents()))).append('\n');
        if (summary.voidsCents() != 0) {
            out.append(row("Voids", "-" + Money.toPlainAmount(summary.voidsCents()))).append('\n');
        }
        out.append(rule()).append('\n');
        out.append(row("Expected cash", Money.toPlainAmount(close.expectedCashCents()))).append('\n');
        out.append(row("Counted cash", Money.toPlainAmount(close.countedCashCents()))).append('\n');
        out.append(row("Over / short", Money.toPlainAmount(close.varianceCents()))).append('\n');
        out.append(rule()).append('\n');
        out.append(center("Shift closed")).append('\n');
        return out.toString();
    }

    private static String rule() {
        return "-".repeat(WIDTH);
    }

    private static String center(String text) {
        String clipped = text.length() > WIDTH ? text.substring(0, WIDTH) : text;
        int left = (WIDTH - clipped.length()) / 2;
        return " ".repeat(left) + clipped;
    }

    /** Left-aligned label and right-aligned amount on one line. */
    private static String row(String label, String amount) {
        int room = WIDTH - amount.length() - 1;
        String clipped = label.length() > room ? label.substring(0, Math.max(0, room)) : label;
        return clipped + " ".repeat(Math.max(1, WIDTH - clipped.length() - amount.length())) + amount;
    }
}
