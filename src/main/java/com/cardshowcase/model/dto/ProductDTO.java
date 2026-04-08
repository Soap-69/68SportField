package com.cardshowcase.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductDTO {

    /** Null on create, set on edit. */
    private Long id;

    @NotBlank(message = "Name is required")
    @Size(max = 300, message = "Name must be at most 300 characters")
    private String name;

    @NotBlank(message = "Slug is required")
    @Size(max = 350, message = "Slug must be at most 350 characters")
    private String slug;

    @NotNull(message = "Category is required")
    private Long categoryId;

    private String description;
    private String highlights;
    private String boxBreakInfo;

    @Size(max = 500, message = "Configuration must be at most 500 characters")
    private String configuration;

    private BigDecimal price;
    private BigDecimal originalPrice;

    @Size(max = 100)
    private String brand;

    @Builder.Default private Boolean isOnSale    = false;
    @Builder.Default private Boolean isNew       = false;
    @Builder.Default private Boolean isTrending  = false;
    @Builder.Default private Boolean isBestSeller = false;
    @Builder.Default private Boolean isPreOrder  = false;
    @Builder.Default private Boolean isFeatured  = false;

    @Builder.Default private Integer sortOrder = 0;
    @Builder.Default private Boolean isActive  = true;
}
