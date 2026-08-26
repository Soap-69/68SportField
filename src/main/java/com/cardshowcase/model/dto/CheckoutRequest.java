package com.cardshowcase.model.dto;

import com.cardshowcase.model.entity.ServiceLevel;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for POST /api/checkout.
 *
 * Guest callers must supply guestName, guestEmail, and all shipping fields.
 * Logged-in customers may supply savedShippingAddressId / savedBillingAddressId
 * (server resolves + snapshots) or provide inline address fields directly.
 *
 * There is intentionally NO cartId field — the cart is always resolved from
 * the current session/cookie. Any cartId sent by the client is silently
 * ignored by Jackson's unknown-properties handling.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CheckoutRequest {

    /** Client-generated idempotency key (UUID recommended). Required. */
    @NotBlank
    private String idempotencyKey;

    /**
     * Requested service level. Defaults to GROUND.
     * Ignored for client-supplied values: Jackson's @JsonIgnoreProperties(ignoreUnknown=true)
     * allows existing callers to omit this field — they'll get GROUND automatically.
     */
    private ServiceLevel serviceLevel = ServiceLevel.GROUND;

    // ── Guest contact (required for guests, ignored for logged-in) ──
    private String guestName;
    private String guestEmail;

    // ── Saved address references (logged-in customers only) ─────────
    private Long savedShippingAddressId;
    private Long savedBillingAddressId;

    // ── Inline shipping address ──────────────────────────────────────
    private String shippingFirstName;
    private String shippingLastName;
    private String shippingAddressLine1;
    private String shippingAddressLine2;
    private String shippingCity;
    private String shippingState;
    private String shippingZip;
    private String shippingCountry;
    private String shippingPhone;

    // ── Billing address ──────────────────────────────────────────────
    /** When true the billing snapshot is copied from the shipping snapshot. */
    private boolean billingSameAsShipping = true;

    private String billingFirstName;
    private String billingLastName;
    private String billingAddressLine1;
    private String billingAddressLine2;
    private String billingCity;
    private String billingState;
    private String billingZip;
    private String billingCountry;
    private String billingPhone;
}
