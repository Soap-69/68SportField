package com.cardshowcase.repository;

import com.cardshowcase.model.entity.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    List<ProductImage> findByProductIdOrderBySortOrderAsc(Long productId);

    /**
     * Returns [productId, imageUrl] pairs for all primary images of the given product IDs.
     * Used on the list page to avoid N+1 queries without a collection fetch join.
     */
    @Query("""
        SELECT pi.product.id, pi.imageUrl
        FROM ProductImage pi
        WHERE pi.product.id IN :productIds
          AND pi.isPrimary = true
        """)
    List<Object[]> findPrimaryImageDataByProductIds(@Param("productIds") List<Long> productIds);
}
