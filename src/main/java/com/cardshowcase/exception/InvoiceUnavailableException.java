package com.cardshowcase.exception;

/** The order exists but has no SUCCEEDED or REFUNDED main payment, so no invoice exists yet. */
public class InvoiceUnavailableException extends RuntimeException {
    public InvoiceUnavailableException(Long orderId) {
        super("No invoice is available for order " + orderId + ": the main payment has not succeeded.");
    }
}
