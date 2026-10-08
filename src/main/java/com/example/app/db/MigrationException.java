package com.example.app.db;

/** An upgrade step failed. Earlier steps stay applied; the failing step is recorded nowhere, so it can be retried. */
public class MigrationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
