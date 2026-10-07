package com.example.app.payment;

import com.example.app.error.BusinessException;

public class PaymentDeclinedException extends BusinessException {

    public PaymentDeclinedException(String message) {
        super(message);
    }
}
