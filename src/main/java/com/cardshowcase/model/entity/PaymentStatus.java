package com.cardshowcase.model.entity;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    REFUNDED,
    PARTIALLY_REFUNDED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> TRANSITIONS;

    static {
        TRANSITIONS = new EnumMap<>(PaymentStatus.class);
        TRANSITIONS.put(PENDING,            EnumSet.of(SUCCEEDED, FAILED));
        TRANSITIONS.put(FAILED,             EnumSet.of(PENDING));   // explicit retry
        TRANSITIONS.put(SUCCEEDED,          EnumSet.of(REFUNDED, PARTIALLY_REFUNDED));
        TRANSITIONS.put(REFUNDED,           EnumSet.noneOf(PaymentStatus.class));
        TRANSITIONS.put(PARTIALLY_REFUNDED, EnumSet.noneOf(PaymentStatus.class));
    }

    public boolean canTransitionTo(PaymentStatus next) {
        return TRANSITIONS.getOrDefault(this, EnumSet.noneOf(PaymentStatus.class)).contains(next);
    }
}
