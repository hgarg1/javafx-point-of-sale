package com.example.app.error;

/** A rule the user can fix (insufficient stock, bad input, wrong shift state). Shown to the user as-is. */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
