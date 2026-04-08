package com.cardshowcase.model.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "inquiries")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "product")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Inquiry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @NotBlank
    @Size(max = 100)
    @Column(name = "customer_name", nullable = false, length = 100)
    private String customerName;

    @NotBlank
    @Email
    @Size(max = 200)
    @Column(name = "customer_email", nullable = false, length = 200)
    private String customerEmail;

    @Size(max = 50)
    @Column(name = "customer_phone", length = 50)
    private String customerPhone;

    @Size(max = 200)
    @Column(name = "customer_company", length = 200)
    private String customerCompany;

    private Integer quantity;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Size(max = 20)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "NEW";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
