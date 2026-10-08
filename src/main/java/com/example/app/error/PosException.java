package com.example.app.error;

/**
 * Base for the errors the till shows the person using it: a rule they can act on, or a role that is not allowed to do
 * something. Anything else is a bug. It is logged, and the person sees a generic message instead.
 */
public abstract class PosException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    protected PosException(String message) {
        super(message);
    }
}
