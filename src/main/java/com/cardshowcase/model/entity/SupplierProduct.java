package com.cardshowcase.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "supplier_products")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"supplier", "variant"})
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SupplierProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "variant_id")
    private ProductVariant variant;

    @Column(name = "external_product_id", length = 200)
    private String externalProductId;

    @Column(name = "supplier_sku", length = 200)
    private String supplierSku;

    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    @Column(name = "supplier_cost", precision = 10, scale = 2)
    private BigDecimal supplierCost;

    @Column(name = "availability_status", length = 50)
    private String availabilityStatus;

    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    @Column(name = "manual_override")
    @Builder.Default
    private Boolean manualOverride = false;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
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
