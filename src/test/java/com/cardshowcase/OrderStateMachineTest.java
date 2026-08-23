package com.cardshowcase;

import com.cardshowcase.model.entity.OrderStatus;
import org.junit.jupiter.api.Test;

import static com.cardshowcase.model.entity.OrderStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the OrderStatus state machine — no Spring context required.
 * Covers every legal transition and a representative set of illegal ones.
 */
class OrderStateMachineTest {

    // ── Legal transitions ──────────────────────────────────────────

    @Test void pendingPayment_to_paid_allowed()          { assertAllowed(PENDING_PAYMENT, PAID); }
    @Test void pendingPayment_to_cancelled_allowed()     { assertAllowed(PENDING_PAYMENT, CANCELLED); }

    @Test void paid_to_processing_allowed()              { assertAllowed(PAID, PROCESSING); }
    @Test void paid_to_refunded_allowed()                { assertAllowed(PAID, REFUNDED); }

    @Test void processing_to_shipped_allowed()           { assertAllowed(PROCESSING, SHIPPED); }
    @Test void processing_to_refunded_allowed()          { assertAllowed(PROCESSING, REFUNDED); }

    @Test void shipped_to_delivered_allowed()            { assertAllowed(SHIPPED, DELIVERED); }
    @Test void shipped_to_refunded_allowed()             { assertAllowed(SHIPPED, REFUNDED); }

    @Test void delivered_to_completed_allowed()          { assertAllowed(DELIVERED, COMPLETED); }
    @Test void delivered_to_refunded_allowed()           { assertAllowed(DELIVERED, REFUNDED); }

    @Test void completed_to_refunded_allowed()           { assertAllowed(COMPLETED, REFUNDED); }

    // ── Terminal states ────────────────────────────────────────────

    @Test void cancelled_is_terminal() {
        for (OrderStatus next : values()) {
            assertThat(CANCELLED.canTransitionTo(next))
                    .as("CANCELLED → %s should be forbidden", next)
                    .isFalse();
        }
    }

    @Test void refunded_is_terminal() {
        for (OrderStatus next : values()) {
            assertThat(REFUNDED.canTransitionTo(next))
                    .as("REFUNDED → %s should be forbidden", next)
                    .isFalse();
        }
    }

    // ── Illegal transitions ────────────────────────────────────────

    /** Key business rule: once payment is captured (PAID), cancellation must
     *  go through the REFUNDED path — direct CANCELLED is not allowed. */
    @Test void paid_cannot_be_cancelled()                { assertForbidden(PAID, CANCELLED); }

    @Test void pendingPayment_to_processing_rejected()   { assertForbidden(PENDING_PAYMENT, PROCESSING); }
    @Test void pendingPayment_to_shipped_rejected()      { assertForbidden(PENDING_PAYMENT, SHIPPED); }
    @Test void pendingPayment_to_refunded_rejected()     { assertForbidden(PENDING_PAYMENT, REFUNDED); }
    @Test void pendingPayment_to_delivered_rejected()    { assertForbidden(PENDING_PAYMENT, DELIVERED); }
    @Test void pendingPayment_to_completed_rejected()    { assertForbidden(PENDING_PAYMENT, COMPLETED); }

    @Test void paid_to_shipped_rejected()                { assertForbidden(PAID, SHIPPED); }
    @Test void paid_to_delivered_rejected()              { assertForbidden(PAID, DELIVERED); }
    @Test void paid_to_completed_rejected()              { assertForbidden(PAID, COMPLETED); }

    @Test void processing_to_paid_rejected()             { assertForbidden(PROCESSING, PAID); }
    @Test void processing_to_delivered_rejected()        { assertForbidden(PROCESSING, DELIVERED); }
    @Test void processing_to_cancelled_rejected()        { assertForbidden(PROCESSING, CANCELLED); }

    @Test void shipped_to_processing_rejected()          { assertForbidden(SHIPPED, PROCESSING); }
    @Test void shipped_to_completed_rejected()           { assertForbidden(SHIPPED, COMPLETED); }
    @Test void shipped_to_cancelled_rejected()           { assertForbidden(SHIPPED, CANCELLED); }

    @Test void delivered_to_shipped_rejected()           { assertForbidden(DELIVERED, SHIPPED); }
    @Test void delivered_to_cancelled_rejected()         { assertForbidden(DELIVERED, CANCELLED); }

    @Test void completed_to_completed_rejected()         { assertForbidden(COMPLETED, COMPLETED); }
    @Test void completed_to_cancelled_rejected()         { assertForbidden(COMPLETED, CANCELLED); }

    // ── Helpers ───────────────────────────────────────────────────

    private void assertAllowed(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to))
                .as("%s → %s should be allowed", from, to)
                .isTrue();
    }

    private void assertForbidden(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to))
                .as("%s → %s should be forbidden", from, to)
                .isFalse();
    }
}
