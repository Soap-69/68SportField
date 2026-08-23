package com.cardshowcase.model.dto;

import com.cardshowcase.model.entity.Order;
import com.cardshowcase.model.entity.OrderItem;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

public record OrderResponse(
        Long id,
        String orderNumber,
        String status,
        BigDecimal subtotal,
        BigDecimal shippingAmount,
        BigDecimal taxAmount,
        BigDecimal total,
        List<OrderItemResponse> items,
        String createdAt
) {
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public static OrderResponse from(Order order, List<OrderItem> items) {
        List<OrderItemResponse> itemDtos = items.stream()
                .map(i -> new OrderItemResponse(
                        i.getId(),
                        i.getProductName(),
                        i.getVariantTypeSnapshot(),
                        i.getSkuSnapshot(),
                        i.getUnitPrice(),
                        i.getQuantity(),
                        i.getLineSubtotal()))
                .toList();

        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus().name(),
                order.getSubtotal(),
                order.getShippingAmount(),
                order.getTaxAmount(),
                order.getTotal(),
                itemDtos,
                order.getCreatedAt() != null ? order.getCreatedAt().format(FMT) : null
        );
    }
}
