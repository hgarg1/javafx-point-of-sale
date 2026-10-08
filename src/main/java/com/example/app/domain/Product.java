package com.example.app.domain;

/**
 * A sellable item. {@code stockQty} is a cached total that is only changed together with a StockMovement row.
 * Prices and tax are in cents and basis points; {@code taxRateBp} of 800 means 8%.
 */
public record Product(
        long id,
        String sku,
        String barcode,
        String name,
        Long categoryId,
        Long supplierId,
        long priceCents,
        long costCents,
        int taxRateBp,
        int stockQty,
        int reorderLevel,
        int reorderQty,
        boolean active,
        ProductKind kind) {

    public Money price() {
        return new Money(priceCents);
    }

    public boolean isGiftCard() {
        return kind == ProductKind.GIFT_CARD;
    }

    /** Gift cards are not stocked, so they are never low on stock. */
    public boolean isLowStock() {
        return active && kind == ProductKind.STOCK && stockQty <= reorderLevel;
    }
}
