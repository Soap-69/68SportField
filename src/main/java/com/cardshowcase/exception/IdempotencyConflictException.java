package com.cardshowcase.exception;

/**
 * Thrown when an idempotency key is reused with different request content.
 * Results in HTTP 409 Conflict.
 */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}
