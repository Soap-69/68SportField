package com.cardshowcase.shipping;

import java.math.BigDecimal;

/**
 * Immutable result of a ShippingService.calculateQuote() call.
 * Pure value object — carries no identity and has no side effects.
 */
public record ShippingQuote(ShippingQuoteStatus status, BigDecimal amount) {

    /** A concrete shipping cost was determined. */
    public static ShippingQuote resolved(BigDecimal amount) {
        return new ShippingQuote(ShippingQuoteStatus.RESOLVED, amount);
    }

    /** Continental US Ground under $500: no approved price; do not invent one. */
    public static ShippingQuote requiresManualQuote() {
        return new ShippingQuote(ShippingQuoteStatus.REQUIRES_MANUAL_QUOTE, null);
    }

    /** AK/HI destination: real cost determined later via Shipment quote flow. */
    public static ShippingQuote akHiDeferred() {
        return new ShippingQuote(ShippingQuoteStatus.AK_HI_DEFERRED, null);
    }

    public boolean isResolved()            { return status == ShippingQuoteStatus.RESOLVED; }
    public boolean isRequiresManualQuote() { return status == ShippingQuoteStatus.REQUIRES_MANUAL_QUOTE; }
    public boolean isAkHiDeferred()        { return status == ShippingQuoteStatus.AK_HI_DEFERRED; }
}
