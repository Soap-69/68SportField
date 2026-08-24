package com.cardshowcase.payment;

public record PaymentResult(
        GatewayOutcome outcome,
        String providerPaymentId,
        String failureCode,
        String failureMessage
) {}
