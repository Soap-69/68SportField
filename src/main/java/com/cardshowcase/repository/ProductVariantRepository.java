package com.cardshowcase.repository;

import com.cardshowcase.model.entity.ProductVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    List<ProductVariant> findByProductId(Long productId);

    List<ProductVariant> findByProductIdAndIsActiveTrue(Long productId);

    Optional<ProductVariant> findByProductIdAndVariantType(Long productId, String variantType);

    Optional<ProductVariant> findBySku(String sku);

    /** True if any variant has this SKU (case-insensitive). Used during create. */
    boolean existsBySkuIgnoreCase(String sku);

    /** True if a DIFFERENT variant (id != excludeId) has this SKU. Used during update. */
    boolean existsBySkuIgnoreCaseAndIdNot(String sku, Long excludeId);

    List<ProductVariant> findByProductIdIn(List<Long> productIds);

    @Query("SELECT v.product.id, COUNT(v) FROM ProductVariant v WHERE v.product.id IN :ids GROUP BY v.product.id")
    List<Object[]> countByProductIds(@Param("ids") List<Long> ids);
}
