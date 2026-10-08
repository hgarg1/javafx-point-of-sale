package com.example.app.error;

/** A business rule that a manager can override by entering their credentials. */
public class ApprovalRequiredException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public ApprovalRequiredException(String message) {
        super(message);
    }
}
