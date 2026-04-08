package com.cardshowcase.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class InquiryDTO {

    private Long productId;

    @NotBlank
    @Size(max = 100)
    private String customerName;

    @NotBlank
    @Email
    @Size(max = 200)
    private String customerEmail;

    @Size(max = 50)
    private String customerPhone;

    @Size(max = 200)
    private String customerCompany;

    private Integer quantity;

    private String message;
}
