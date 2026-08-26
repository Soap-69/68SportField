package com.cardshowcase.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"customer", "items"})
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "order_number", nullable = false, unique = true, length = 50)
    private String orderNumber;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 200)
    private String idempotencyKey;

    /** SHA-256 hex of cart contents + address + customer identity at checkout time.
     *  Used to detect idempotency key reuse with different request content. */
    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status;

    // ── Guest identity ─────────────────────────────────────────────
    @Column(name = "guest_name", length = 200)
    private String guestName;

    @Column(name = "guest_email", length = 200)
    private String guestEmail;

    /**
     * SHA-256 hex digest of the guest cart session token captured at checkout time,
     * stored for guest orders only. The raw cookie value is never persisted; only the
     * hash is stored so idempotency replay verification can confirm the same session
     * without exposing the token if the DB is compromised. NULL for customer orders.
     */
    @Column(name = "guest_cart_token_hash", length = 64)
    private String guestCartTokenHash;

    // ── Shipping address snapshot ──────────────────────────────────
    @Column(name = "shipping_first_name", length = 100)
    private String shippingFirstName;

    @Column(name = "shipping_last_name", length = 100)
    private String shippingLastName;

    @Column(name = "shipping_address_line1", length = 300)
    private String shippingAddressLine1;

    @Column(name = "shipping_address_line2", length = 300)
    private String shippingAddressLine2;

    @Column(name = "shipping_city", length = 100)
    private String shippingCity;

    @Column(name = "shipping_state", length = 50)
    private String shippingState;

    @Column(name = "shipping_zip", length = 20)
    private String shippingZip;

    @Column(name = "shipping_country", length = 50)
    private String shippingCountry;

    @Column(name = "shipping_phone", length = 50)
    private String shippingPhone;

    // ── Billing address snapshot ───────────────────────────────────
    @Column(name = "billing_first_name", length = 100)
    private String billingFirstName;

    @Column(name = "billing_last_name", length = 100)
    private String billingLastName;

    @Column(name = "billing_address_line1", length = 300)
    private String billingAddressLine1;

    @Column(name = "billing_address_line2", length = 300)
    private String billingAddressLine2;

    @Column(name = "billing_city", length = 100)
    private String billingCity;

    @Column(name = "billing_state", length = 50)
    private String billingState;

    @Column(name = "billing_zip", length = 20)
    private String billingZip;

    @Column(name = "billing_country", length = 50)
    private String billingCountry;

    @Column(name = "billing_phone", length = 50)
    private String billingPhone;

    // ── Financials ─────────────────────────────────────────────────
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "shipping_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal shippingAmount;

    @Column(name = "tax_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal taxAmount;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal total;

    // ── Shipment (1:1, read-only navigation — Week 6) ─────────────
    @OneToOne(mappedBy = "order", fetch = FetchType.LAZY)
    private Shipment shipment;

    // ── Items ──────────────────────────────────────────────────────
    @Builder.Default
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    private List<OrderItem> items = new ArrayList<>();

    // ── Timestamps ─────────────────────────────────────────────────
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
