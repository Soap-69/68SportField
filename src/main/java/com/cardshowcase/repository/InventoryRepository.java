package com.cardshowcase.repository;

import com.cardshowcase.model.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    List<Inventory> findByVariantId(Long variantId);

    Optional<Inventory> findByVariantIdAndLocationId(Long variantId, Long locationId);

    List<Inventory> findByVariantIdIn(List<Long> variantIds);

    @Query("SELECT SUM(i.quantity) FROM Inventory i WHERE i.variant.id = :variantId")
    Integer getTotalStock(@Param("variantId") Long variantId);

    @Query("SELECT i FROM Inventory i WHERE i.quantity <= i.lowStockThreshold")
    List<Inventory> findLowStockItems();

    @Query("SELECT i.variant.id, SUM(i.quantity) FROM Inventory i WHERE i.variant.id IN :variantIds GROUP BY i.variant.id")
    List<Object[]> sumStockByVariantIds(@Param("variantIds") List<Long> variantIds);

    @Query("SELECT v.product.id, COALESCE(SUM(i.quantity), 0) FROM Inventory i JOIN i.variant v WHERE v.product.id IN :productIds GROUP BY v.product.id")
    List<Object[]> sumStockByProductIds(@Param("productIds") List<Long> productIds);
}
