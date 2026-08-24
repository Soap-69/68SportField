package com.cardshowcase.exception;

/**
 * Thrown when an optimistic-lock conflict occurs during inventory deduction in
 * {@link com.cardshowcase.service.PaymentService#confirmSuccessfulPayment}.
 *
 * IMPORTANT: This exception causes the entire confirmation transaction to roll back.
 * The Payment remains in its pre-confirmation state (typically PENDING).
 * This is NOT a business failure — do NOT mark the Payment FAILED on a lock conflict.
 * The caller should treat this as a clean, retryable concurrency error.
 */
public class PaymentConcurrencyException extends RuntimeException {
    public PaymentConcurrencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
