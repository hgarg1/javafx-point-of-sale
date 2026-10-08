package com.example.app.service;

import com.example.app.domain.Money;
import com.example.app.domain.Product;
import com.example.app.error.BusinessException;
import com.example.app.service.CheckoutService.CartItem;
import com.example.app.service.CheckoutService.LineQuote;
import com.example.app.service.CheckoutService.Quote;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a cart into amounts. Pure: it works only from the product data it is given and writes nothing, so the rounding
 * rules can be tested without a database. Every amount is rounded HALF_UP to the cent, and tax is worked out line by
 * line, so the receipt adds up exactly.
 */
final class Pricing {

    /** One cart line priced from current product data. */
    record PricedLine(Product product, CartItem item, long discountCents, long taxCents, long totalCents) {
    }

    /** The priced lines, which the sale records, and the quote, which the screen shows. */
    record PricedCart(List<PricedLine> lines, Quote quote) {
    }

    private Pricing() {
    }

    /**
     * Prices each line. The gross is the unit price times the quantity, the discount comes off the gross, and tax is
     * charged on what is left. Gift cards are sold at face value, so they cannot be discounted and carry no tax.
     */
    static PricedCart price(List<CartItem> cart, Map<Long, Product> productsById) {
        List<PricedLine> lines = new ArrayList<>();
        List<LineQuote> lineQuotes = new ArrayList<>();
        long subtotal = 0;
        long discountTotal = 0;
        long taxTotal = 0;
        long total = 0;
        for (CartItem item : cart) {
            Product product = productsById.get(item.productId());
            if (product.isGiftCard() && item.discountBp() > 0) {
                throw new BusinessException("Gift cards cannot be discounted");
            }
            long gross = Math.multiplyExact(product.priceCents(), (long) item.qty());
            long discount = Money.percentOf(gross, item.discountBp());
            long net = gross - discount;
            long tax = product.isGiftCard() ? 0 : Money.percentOf(net, product.taxRateBp());
            long lineTotal = net + tax;
            lines.add(new PricedLine(product, item, discount, tax, lineTotal));
            lineQuotes.add(new LineQuote(product.id(), product.name(), product.priceCents(), item.qty(),
                    item.discountBp(), discount, tax, lineTotal));
            subtotal += gross;
            discountTotal += discount;
            taxTotal += tax;
            total += lineTotal;
        }
        return new PricedCart(lines, new Quote(lineQuotes, subtotal, discountTotal, taxTotal, total));
    }
}
