package com.cardshowcase.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "cart_items")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = {"cart", "variant"})
public class CartItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "added_at", updatable = false)
    private LocalDateTime addedAt;

    @PrePersist
    void prePersist() { addedAt = LocalDateTime.now(); }

    /** Returns effective unit price — delegates to variant's own logic. */
    public BigDecimal getEffectivePrice() {
        if (variant == null) return BigDecimal.ZERO;
        return variant.getEffectivePrice();
    }

    /** Line subtotal = effectivePrice × quantity */
    public BigDecimal getSubtotal() {
        return getEffectivePrice().multiply(BigDecimal.valueOf(quantity != null ? quantity : 0));
    }
}
