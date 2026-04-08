package com.cardshowcase.model.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class BannerDTO {

    private Long id;

    @Size(max = 200)
    private String title;

    @Size(max = 500)
    private String linkUrl;

    private Integer sortOrder = 0;

    private Boolean isActive = true;
}
