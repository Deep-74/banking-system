package com.banking.frauddetectionservice.exception;

public class FraudDetectionException extends RuntimeException {
    public FraudDetectionException(String message) {
        super(message);
    }

    public FraudDetectionException(String message, Throwable cause) {
        super(message, cause);
    }
}

