package com.cardshowcase.repository;

import com.cardshowcase.model.entity.SupplierProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierProductRepository extends JpaRepository<SupplierProduct, Long> {

    List<SupplierProduct> findByVariantId(Long variantId);

    List<SupplierProduct> findBySupplierId(Long supplierId);
}
