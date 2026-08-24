package com.cardshowcase.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment_events",
       uniqueConstraints = @UniqueConstraint(name = "uq_payment_events_event_id", columnNames = "event_id"))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@ToString(exclude = "payment")
public class PaymentEvent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    /** UUID generated at event creation time — globally unique event identifier. */
    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private PaymentEventType eventType;

    @Column(nullable = false, length = 50)
    private String provider;

    /**
     * Non-sensitive metadata only (provider transaction id, result code, masked last4, etc.).
     * MUST NOT contain full card number, CVV, authorization secrets, or raw payment credentials.
     */
    @Column(length = 1000)
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist void onCreate() { createdAt = LocalDateTime.now(); }
}
