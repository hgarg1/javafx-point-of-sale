package com.example.app.domain;

/**
 * The codes of the built-in tenders. A built-in tender uses its kind's name as its code, so the code of an existing payment
 * still means the same tender after the tenders became configurable. Tenders added by the store get generated codes.
 */
public final class TenderCodes {

    public static final String CASH = "CASH";
    public static final String CARD = "CARD";
    public static final String EXTERNAL = "EXTERNAL";
    public static final String STORED_VALUE = "STORED_VALUE";

    private TenderCodes() {
    }

    public static boolean isBuiltIn(String code) {
        for (TenderKind kind : TenderKind.values()) {
            if (kind.name().equals(code)) {
                return true;
            }
        }
        return false;
    }
}
