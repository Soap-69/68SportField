package com.cardshowcase.model.entity;

/**
 * Tracks the state of the shipping charge only — never fulfillment readiness.
 *
 * Legal transitions:
 *   QUOTE_REQUIRED → QUOTED → PAYMENT_PENDING → PAID
 *   QUOTED         → WAIVED
 *   PAYMENT_PENDING → WAIVED
 *
 * NOT_REQUIRED, PAID, and WAIVED are terminal.
 */
public enum ShippingPaymentStatus {

    /** Continental US orders where shipping was resolved at checkout — no supplemental charge needed. */
    NOT_REQUIRED,

    /** Awaiting admin to enter the quoted shipping cost (AK/HI or continental Ground < $500). */
    QUOTE_REQUIRED,

    /** Admin has entered quoted_shipping_amount; not yet sent to customer for payment. */
    QUOTED,

    /** Quote given; awaiting customer supplemental shipping payment. */
    PAYMENT_PENDING,

    /** Supplemental shipping payment confirmed received. */
    PAID,

    /** Admin explicitly waived the supplemental charge. */
    WAIVED;

    public boolean canTransitionTo(ShippingPaymentStatus next) {
        return switch (this) {
            case QUOTE_REQUIRED  -> next == QUOTED;
            case QUOTED          -> next == PAYMENT_PENDING || next == WAIVED;
            case PAYMENT_PENDING -> next == PAID || next == WAIVED;
            case NOT_REQUIRED, PAID, WAIVED -> false;
        };
    }
}
