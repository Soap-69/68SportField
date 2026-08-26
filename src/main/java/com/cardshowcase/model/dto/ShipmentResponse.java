package com.cardshowcase.model.dto;

import com.cardshowcase.model.entity.Shipment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ShipmentResponse(
        Long id,
        Long orderId,
        String carrier,
        String serviceLevel,
        BigDecimal quotedShippingAmount,
        String shippingPaymentStatus,
        String trackingNumber,
        LocalDateTime quotedAt,
        LocalDateTime shippingPaidAt,
        LocalDateTime shippedAt,
        LocalDateTime deliveredAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ShipmentResponse from(Shipment s) {
        return new ShipmentResponse(
                s.getId(),
                s.getOrder().getId(),
                s.getCarrier(),
                s.getServiceLevel() != null ? s.getServiceLevel().name() : null,
                s.getQuotedShippingAmount(),
                s.getShippingPaymentStatus().name(),
                s.getTrackingNumber(),
                s.getQuotedAt(),
                s.getShippingPaidAt(),
                s.getShippedAt(),
                s.getDeliveredAt(),
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }
}
