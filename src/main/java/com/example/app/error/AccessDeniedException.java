package com.example.app.error;

/** The current user's role is not allowed to perform the action. */
public class AccessDeniedException extends PosException {

    private static final long serialVersionUID = 1L;

    public AccessDeniedException(String message) {
        super(message);
    }
}
