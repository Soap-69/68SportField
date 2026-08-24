package com.cardshowcase.model.dto;

import com.cardshowcase.model.entity.Payment;
import java.math.BigDecimal;

public record PaymentResponse(
        Long id,
        Long orderId,
        String provider,
        String status,
        BigDecimal amount,
        String currency,
        String providerPaymentId,
        String failureCode,
        String failureMessage
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getOrder().getId(),
                payment.getProvider(),
                payment.getStatus().name(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getProviderPaymentId(),
                payment.getFailureCode(),
                payment.getFailureMessage()
        );
    }
}
