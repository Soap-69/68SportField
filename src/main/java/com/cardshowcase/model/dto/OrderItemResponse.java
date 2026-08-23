package com.cardshowcase.model.dto;

import java.math.BigDecimal;

public record OrderItemResponse(
        Long id,
        String productName,
        String variantType,
        String sku,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineSubtotal
) {}
