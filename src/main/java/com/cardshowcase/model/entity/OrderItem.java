package com.cardshowcase.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "order_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"order", "productVariant"})
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** Nullable FK — preserved for reference but the order history never depends on it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_variant_id")
    private ProductVariant productVariant;

    // ── Immutable snapshots captured at order creation time ────────
    @Column(name = "product_name", nullable = false, length = 300)
    private String productName;

    @Column(name = "sku_snapshot", length = 100)
    private String skuSnapshot;

    @Column(name = "variant_type_snapshot", nullable = false, length = 50)
    private String variantTypeSnapshot;

    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "line_subtotal", nullable = false, precision = 10, scale = 2)
    private BigDecimal lineSubtotal;
}
