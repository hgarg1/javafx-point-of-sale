package com.example.app.domain;

/** The editable fields of a product. Stock is not included; it changes only through stock movements. */
public record ProductDraft(
        String sku,
        String barcode,
        String name,
        Long categoryId,
        Long supplierId,
        long priceCents,
        long costCents,
        int taxRateBp,
        int reorderLevel,
        int reorderQty,
        boolean active,
        ProductKind kind) {

    /** A product with no standing order quantity, so reorder suggestions use twice the reorder level. */
    public ProductDraft(String sku, String barcode, String name, Long categoryId, Long supplierId, long priceCents,
                        long costCents, int taxRateBp, int reorderLevel, boolean active, ProductKind kind) {
        this(sku, barcode, name, categoryId, supplierId, priceCents, costCents, taxRateBp, reorderLevel, 0, active,
                kind);
    }

    /** A stock product, which is what every existing caller creates. */
    public ProductDraft(String sku, String barcode, String name, Long categoryId, Long supplierId, long priceCents,
                        long costCents, int taxRateBp, int reorderLevel, boolean active) {
        this(sku, barcode, name, categoryId, supplierId, priceCents, costCents, taxRateBp, reorderLevel, active,
                ProductKind.STOCK);
    }
}
