package com.example.app.domain;

/**
 * A line on a sale. Name and price are copied at sale time so history survives later product edits.
 * {@code lineTotalCents} includes tax.
 */
public record SaleLine(
        long id,
        long saleId,
        long productId,
        String nameSnapshot,
        long unitPriceCents,
        int qty,
        int discountBp,
        long discountCents,
        long taxCents,
        long lineTotalCents) {

    /** The line total before tax. */
    public long netCents() {
        return lineTotalCents - taxCents;
    }
}
