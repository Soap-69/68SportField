package com.cardshowcase.model.entity;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Order lifecycle state machine.
 *
 * Legal transitions:
 *   PENDING_PAYMENT → PAID, CANCELLED
 *   PAID            → PROCESSING, REFUNDED      (cannot be CANCELLED — must go through REFUNDED)
 *   PROCESSING      → SHIPPED, REFUNDED
 *   SHIPPED         → DELIVERED, REFUNDED
 *   DELIVERED       → COMPLETED, REFUNDED
 *   COMPLETED       → REFUNDED
 *   CANCELLED       → (terminal)
 *   REFUNDED        → (terminal)
 */
public enum OrderStatus {
    PENDING_PAYMENT,
    PAID,
    PROCESSING,
    SHIPPED,
    DELIVERED,
    COMPLETED,
    CANCELLED,
    REFUNDED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED;

    static {
        Map<OrderStatus, Set<OrderStatus>> m = new EnumMap<>(OrderStatus.class);
        m.put(PENDING_PAYMENT, EnumSet.of(PAID, CANCELLED));
        m.put(PAID,            EnumSet.of(PROCESSING, REFUNDED));
        m.put(PROCESSING,      EnumSet.of(SHIPPED, REFUNDED));
        m.put(SHIPPED,         EnumSet.of(DELIVERED, REFUNDED));
        m.put(DELIVERED,       EnumSet.of(COMPLETED, REFUNDED));
        m.put(COMPLETED,       EnumSet.of(REFUNDED));
        m.put(CANCELLED,       EnumSet.noneOf(OrderStatus.class));
        m.put(REFUNDED,        EnumSet.noneOf(OrderStatus.class));
        ALLOWED = Collections.unmodifiableMap(m);
    }

    public boolean canTransitionTo(OrderStatus next) {
        return ALLOWED.getOrDefault(this, EnumSet.noneOf(OrderStatus.class)).contains(next);
    }
}
