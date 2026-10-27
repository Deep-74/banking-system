package com.banking.paymentservice.exception;

public class RazorpayException extends RuntimeException {
    public RazorpayException(String message, Throwable cause) {
        super(message, cause);
    }

    public RazorpayException(String message) {
        super(message);
    }
}

