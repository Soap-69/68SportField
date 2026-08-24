package com.cardshowcase.payment;

import java.math.BigDecimal;

/**
 * Coarse-grained payment request passed to a PaymentGateway.
 * {@code simulatedOutcome} is used only by {@link ManualPaymentGateway} — real gateways ignore it.
 */
public record PaymentRequest(
        Long orderId,
        Long paymentId,
        BigDecimal amount,
        String currency,
        String idempotencyKey,
        GatewayOutcome simulatedOutcome
) {}
