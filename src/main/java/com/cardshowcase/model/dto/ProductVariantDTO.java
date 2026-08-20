package com.cardshowcase.model.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class ProductVariantDTO {

    private String variantType;
    private String sku;
    private BigDecimal price;
    private BigDecimal salePrice;
    private BigDecimal weight;
    private Boolean isActive = true;
}
