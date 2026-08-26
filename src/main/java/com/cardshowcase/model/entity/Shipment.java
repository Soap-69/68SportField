package com.cardshowcase.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One Shipment per Order (1:1, parallel to Order → Payment from Week 5).
 *
 * Tracks shipping charge state (ShippingPaymentStatus) and carrier/tracking info.
 * ShippingPaymentStatus is the single source of truth for the shipping charge lifecycle;
 * it does NOT encode fulfillment readiness (see OrderService.isReadyForFulfillment).
 */
@Entity
@Table(name = "shipments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /** Owning side of the Order ↔ Shipment 1:1 relationship. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Order order;

    /** Carrier name (e.g. "UPS", "FedEx"). Set when tracking is recorded. */
    @Column(name = "carrier", length = 50)
    private String carrier;

    /**
     * Service level chosen at checkout for continental US orders.
     * Null for AK/HI Shipments created at PAID transition — the actual carrier
     * service is determined by the admin when entering the quote.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "service_level", length = 30)
    private ServiceLevel serviceLevel;

    /** Actual shipping cost quoted by admin. Null until QUOTED status is reached. */
    @Column(name = "quoted_shipping_amount", precision = 10, scale = 2)
    private BigDecimal quotedShippingAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "shipping_payment_status", nullable = false, length = 30)
    private ShippingPaymentStatus shippingPaymentStatus;

    @Column(name = "tracking_number", length = 200)
    private String trackingNumber;

    @Column(name = "quoted_at")
    private LocalDateTime quotedAt;

    @Column(name = "shipping_paid_at")
    private LocalDateTime shippingPaidAt;

    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

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
