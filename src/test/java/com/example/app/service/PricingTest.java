package com.example.app.service;

import com.example.app.domain.Product;
import com.example.app.domain.ProductKind;
import com.example.app.error.BusinessException;
import com.example.app.service.CheckoutService.CartItem;
import com.example.app.service.CheckoutService.LineQuote;
import com.example.app.service.CheckoutService.Quote;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.example.app.domain.ProductKind.STOCK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pricing without a database: the rounding rules, which are the easiest place for a receipt to be a cent out. */
class PricingTest {

    @Test
    void aHalfCentRoundsUpOnTheDiscount() {
        // 5 cents with 50% off is 2.5 cents off, which rounds up to 3, leaving 2.
        Quote quote = price(List.of(new CartItem(1, 1, 5_000)), product(1, 5, 0, STOCK));
        assertEquals(3, quote.discountCents());
        assertEquals(2, quote.totalCents());
    }

    @Test
    void aHalfCentRoundsUpOnTax() {
        // 5 cents at 10% tax is 0.5 cents of tax, which rounds up to 1.
        Quote quote = price(List.of(new CartItem(1, 1, 0)), product(1, 5, 1_000, STOCK));
        assertEquals(1, quote.taxCents());
        assertEquals(6, quote.totalCents());
    }

    @Test
    void taxIsRoundedOnEachLineSoTheReceiptAddsUp() {
        // Three at 1.99 with 8.25% tax: 597 cents of goods carry 49.2525 cents of tax, which is 49 on the line.
        Quote quote = price(List.of(new CartItem(1, 3, 0)), product(1, 199, 825, STOCK));
        assertEquals(597, quote.subtotalCents());
        assertEquals(49, quote.taxCents());
        assertEquals(646, quote.totalCents());
    }

    @Test
    void theDiscountComesOffBeforeTax() {
        // 10.00 less 10% is 9.00, and 8% tax on 9.00 is 0.72 (not 0.80).
        Quote quote = price(List.of(new CartItem(1, 1, 1_000)), product(1, 1_000, 800, STOCK));
        assertEquals(100, quote.discountCents());
        assertEquals(72, quote.taxCents());
        assertEquals(972, quote.totalCents());
    }

    @Test
    void theTotalsAreTheSumOfTheLines() {
        Product tea = product(1, 199, 825, STOCK);
        Product cake = product(2, 400, 0, STOCK);
        Quote quote = price(List.of(new CartItem(1, 3, 0), new CartItem(2, 2, 1_000)), tea, cake);

        assertEquals(597 + 800, quote.subtotalCents());
        assertEquals(80, quote.discountCents());
        assertEquals(49, quote.taxCents());
        assertEquals(646 + 720, quote.totalCents());
        long summed = quote.lines().stream().mapToLong(LineQuote::totalCents).sum();
        assertEquals(summed, quote.totalCents());
    }

    @Test
    void giftCardsCarryNoTaxAndCannotBeDiscounted() {
        Product card = product(1, 2_500, 800, ProductKind.GIFT_CARD);
        Quote quote = price(List.of(new CartItem(1, 1, 0)), card);
        assertEquals(0, quote.taxCents());
        assertEquals(2_500, quote.totalCents());

        BusinessException e = assertThrows(BusinessException.class,
                () -> price(List.of(new CartItem(1, 1, 500)), card));
        assertEquals("Gift cards cannot be discounted", e.getMessage());
    }

    private static Quote price(List<CartItem> cart, Product... products) {
        Map<Long, Product> byId = new HashMap<>();
        for (Product product : products) {
            byId.put(product.id(), product);
        }
        return Pricing.price(cart, byId).quote();
    }

    private static Product product(long id, long priceCents, int taxRateBp, ProductKind kind) {
        return new Product(id, "SKU" + id, null, "Item " + id, null, null, priceCents, 0, taxRateBp, 10, 0, 0, true,
                kind);
    }
}
