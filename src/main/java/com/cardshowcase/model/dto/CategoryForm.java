package com.cardshowcase.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryForm {

    /** Null when creating, set when editing. */
    private Long id;

    /** Null → L1, L1 id → L2, L2 id → L3. L3 parents are rejected server-side. */
    private Long parentId;

    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name must be at most 100 characters")
    private String name;

    @NotBlank(message = "Slug is required")
    @Size(max = 120, message = "Slug must be at most 120 characters")
    private String slug;

    @Size(max = 500, message = "Image URL must be at most 500 characters")
    private String imageUrl;

    @Builder.Default
    private Integer sortOrder = 0;

    @Builder.Default
    private Boolean isActive = true;
}
