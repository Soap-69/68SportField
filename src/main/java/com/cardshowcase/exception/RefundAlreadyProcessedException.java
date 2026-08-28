package com.cardshowcase.exception;

public class RefundAlreadyProcessedException extends RuntimeException {
    public RefundAlreadyProcessedException(String message) {
        super(message);
    }
}
