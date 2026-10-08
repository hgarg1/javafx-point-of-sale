package com.example.app.domain;

/**
 * What a tender does when it is used. Each tender is one kind, and the kind decides the rules: only cash gives change and
 * goes in the drawer, and every other kind needs a reference such as a card number or a wallet reference.
 */
public enum TenderKind {
    CASH("Cash", "", false),
    CARD("Card", "Card number", true),
    EXTERNAL("QR, wallet or voucher", "Wallet reference", true),
    STORED_VALUE("Gift card or store credit", "Gift card or credit code", true);

    private final String label;
    private final String referencePrompt;
    private final boolean needsReference;

    TenderKind(String label, String referencePrompt, boolean needsReference) {
        this.label = label;
        this.referencePrompt = referencePrompt;
        this.needsReference = needsReference;
    }

    /** The name the kind is shown under when someone chooses what sort of payment type to add. */
    public String label() {
        return label;
    }

    /** What the cashier is asked for when a payment of this kind is taken. Empty for cash. */
    public String referencePrompt() {
        return referencePrompt;
    }

    /** Whether a payment of this kind must carry a reference. */
    public boolean needsReference() {
        return needsReference;
    }
}
