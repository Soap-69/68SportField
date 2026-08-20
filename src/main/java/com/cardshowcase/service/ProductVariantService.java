package com.cardshowcase.service;

import com.cardshowcase.model.dto.ProductVariantDTO;
import com.cardshowcase.model.entity.Product;
import com.cardshowcase.model.entity.ProductVariant;
import com.cardshowcase.repository.ProductRepository;
import com.cardshowcase.repository.ProductVariantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductVariantService {

    private final ProductVariantRepository variantRepo;
    private final ProductRepository productRepo;
    private final FileUploadService fileUploadService;

    public List<ProductVariant> getVariantsByProduct(Long productId) {
        return variantRepo.findByProductId(productId);
    }

    public List<ProductVariant> getActiveVariantsByProduct(Long productId) {
        return variantRepo.findByProductIdAndIsActiveTrue(productId);
    }

    public ProductVariant getVariantById(Long id) {
        return variantRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Variant not found: " + id));
    }

    @Transactional
    public ProductVariant createVariant(Long productId, ProductVariantDTO dto, MultipartFile imageFile) {
        Product product = productRepo.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));

        if (variantRepo.findByProductIdAndVariantType(productId, dto.getVariantType()).isPresent()) {
            throw new IllegalStateException(
                    "A " + dto.getVariantType() + " variant already exists for this product.");
        }

        String sku = dto.getSku() != null ? dto.getSku().trim() : null;
        if (sku != null && !sku.isBlank() && variantRepo.existsBySkuIgnoreCase(sku)) {
            throw new IllegalStateException("SKU already in use by another variant: " + sku);
        }

        ProductVariant variant = ProductVariant.builder()
                .product(product)
                .variantType(dto.getVariantType())
                .sku(sku != null && !sku.isBlank() ? sku : null)
                .price(dto.getPrice())
                .salePrice(dto.getSalePrice())
                .weight(dto.getWeight())
                .isActive(dto.getIsActive() != null ? dto.getIsActive() : true)
                .build();

        if (imageFile != null && !imageFile.isEmpty()) {
            variant.setImageUrl(fileUploadService.uploadFile(imageFile, "variants"));
        }

        return variantRepo.save(variant);
    }

    @Transactional
    public ProductVariant updateVariant(Long id, ProductVariantDTO dto, MultipartFile imageFile) {
        ProductVariant variant = getVariantById(id);

        String sku = dto.getSku() != null ? dto.getSku().trim() : null;
        if (sku != null && !sku.isBlank() && variantRepo.existsBySkuIgnoreCaseAndIdNot(sku, id)) {
            throw new IllegalStateException("SKU already in use by another variant: " + sku);
        }

        variant.setSku(sku);
        variant.setPrice(dto.getPrice());
        variant.setSalePrice(dto.getSalePrice());
        variant.setWeight(dto.getWeight());
        variant.setIsActive(dto.getIsActive() != null ? dto.getIsActive() : true);

        if (imageFile != null && !imageFile.isEmpty()) {
            if (variant.getImageUrl() != null) {
                fileUploadService.deleteFile(variant.getImageUrl());
            }
            variant.setImageUrl(fileUploadService.uploadFile(imageFile, "variants"));
        }

        return variantRepo.save(variant);
    }

    @Transactional
    public void deleteVariant(Long id) {
        ProductVariant variant = getVariantById(id);
        if (variant.getImageUrl() != null) {
            fileUploadService.deleteFile(variant.getImageUrl());
        }
        variantRepo.delete(variant);
    }

    @Transactional
    public ProductVariant toggleActive(Long id) {
        ProductVariant variant = getVariantById(id);
        variant.setIsActive(!Boolean.TRUE.equals(variant.getIsActive()));
        return variantRepo.save(variant);
    }

    /** Returns variant count per product id, for the admin product list page. */
    public Map<Long, Long> getVariantCountsByProductIds(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) return Map.of();
        return variantRepo.countByProductIds(productIds).stream()
                .collect(Collectors.toMap(r -> (Long) r[0], r -> (Long) r[1]));
    }
}
