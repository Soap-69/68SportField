package com.cardshowcase.model.entity;

public enum PaymentEventType {
    PAYMENT_CREATED,
    PAYMENT_SUCCEEDED,
    PAYMENT_FAILED,
    PAYMENT_RETRY_STARTED,
    PAYMENT_REFUNDED
}
