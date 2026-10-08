package com.example.app.payment;

import com.example.app.error.BusinessException;

public class PaymentDeclinedException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public PaymentDeclinedException(String message) {
        super(message);
    }
}
