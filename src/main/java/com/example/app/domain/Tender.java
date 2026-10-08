package com.example.app.domain;

/**
 * A payment type the till offers. {@code code} identifies it on every payment and refund and never changes; {@code name}
 * is what the cashier and the receipt show. {@code kind} decides its behaviour. Built-in tenders cannot be deleted.
 */
public record Tender(
        String code,
        String name,
        TenderKind kind,
        boolean enabled,
        int sortOrder,
        boolean countsInDrawer,
        boolean requiresReference) {

    public boolean isCash() {
        return kind == TenderKind.CASH;
    }

    public boolean isBuiltIn() {
        return TenderCodes.isBuiltIn(code);
    }
}
