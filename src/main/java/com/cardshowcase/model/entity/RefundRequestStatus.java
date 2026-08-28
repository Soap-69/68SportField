package com.cardshowcase.model.entity;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * State machine for RefundRequest lifecycle.
 *
 * Legal transitions:
 *   PENDING_APPROVAL → APPROVED, REJECTED
 *   APPROVED         → EXECUTED, FAILED
 *   REJECTED         → (terminal)
 *   EXECUTED         → (terminal)
 *   FAILED           → (terminal)
 */
public enum RefundRequestStatus {
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    EXECUTED,
    FAILED;

    private static final Map<RefundRequestStatus, Set<RefundRequestStatus>> ALLOWED;

    static {
        Map<RefundRequestStatus, Set<RefundRequestStatus>> m = new EnumMap<>(RefundRequestStatus.class);
        m.put(PENDING_APPROVAL, EnumSet.of(APPROVED, REJECTED));
        m.put(APPROVED,         EnumSet.of(EXECUTED, FAILED));
        m.put(REJECTED,         EnumSet.noneOf(RefundRequestStatus.class));
        m.put(EXECUTED,         EnumSet.noneOf(RefundRequestStatus.class));
        m.put(FAILED,           EnumSet.noneOf(RefundRequestStatus.class));
        ALLOWED = Collections.unmodifiableMap(m);
    }

    public boolean canTransitionTo(RefundRequestStatus next) {
        return ALLOWED.getOrDefault(this, EnumSet.noneOf(RefundRequestStatus.class)).contains(next);
    }
}
