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
        boolean active) {
}
