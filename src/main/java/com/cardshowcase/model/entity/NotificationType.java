package com.cardshowcase.model.entity;

/**
 * The six transactional notifications. Each is enqueued exactly once per order,
 * inside the business transaction named here, and nowhere else.
 */
public enum NotificationType {
    /** Customer: checkout created the order (PENDING_PAYMENT, not paid). */
    ORDER_RECEIVED,
    /** Customer: main payment SUCCEEDED, Order PAID; invoice PDF attached. */
    PAYMENT_CONFIRMED,
    /** Admin: same event as PAYMENT_CONFIRMED, sent to app.notification.admin-email. */
    ADMIN_NEW_PAID_ORDER,
    /** Customer: supplemental shipping quote moved QUOTED -> PAYMENT_PENDING. */
    SHIPPING_PAYMENT_REQUESTED,
    /** Customer: Order PROCESSING -> SHIPPED by dispatchShipment. */
    SHIPPED,
    /** Customer: refund executed, Payment and Order REFUNDED. */
    REFUND_EXECUTED
}
