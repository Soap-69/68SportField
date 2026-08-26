package com.cardshowcase.shipping;

/**
 * Outcome category of a ShippingService quote calculation.
 * This enum is internal to the shipping rules engine and is not persisted.
 */
public enum ShippingQuoteStatus {

    /** A concrete dollar amount was determined; Order.shipping_amount should be set to quote.amount(). */
    RESOLVED,

    /**
     * Continental US, Ground, subtotal < $500: no approved rate exists.
     * Do NOT invent a price. Order.shipping_amount = $0 placeholder;
     * surface a "shipping will be quoted separately" message in the UI.
     */
    REQUIRES_MANUAL_QUOTE,

    /**
     * Destination is AK or HI: real shipping cost determined after the product
     * payment is confirmed via the supplemental Shipment quote flow.
     * Order.shipping_amount = $0 placeholder at checkout time.
     */
    AK_HI_DEFERRED
}
