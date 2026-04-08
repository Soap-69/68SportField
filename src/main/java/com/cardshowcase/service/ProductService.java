package com.cardshowcase.service;

import com.cardshowcase.model.dto.ProductDTO;
import com.cardshowcase.model.entity.Category;
import com.cardshowcase.model.entity.Product;
import com.cardshowcase.model.entity.ProductImage;
import com.cardshowcase.repository.CategoryRepository;
import com.cardshowcase.repository.ProductImageRepository;
import com.cardshowcase.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;
import java.util.Optional;

@Service
@Transactional
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository      productRepository;
    private final ProductImageRepository productImageRepository;
    private final CategoryRepository     categoryRepository;
    private final FileUploadService      fileUploadService;
    private final com.cardshowcase.repository.InquiryRepository inquiryRepository;

    // ── Queries ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<Product> getProductList(String search, Long categoryId,
                                        String status, Pageable pageable) {
        Specification<Product> spec = Specification
                .where(ProductSpecification.hasNameLike(search))
                .and(ProductSpecification.hasCategoryId(categoryId))
                .and(ProductSpecification.hasStatus(status));
        return productRepository.findAll(spec, pageable);
    }

    /** Light load — no images. Suitable for toggle / flag operations. */
    @Transactional(readOnly = true)
    public Product getProductById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + id));
    }

    /** Full load — images + 3-level category chain. For edit form and delete. */
    @Transactional(readOnly = true)
    public Product getProductByIdWithImages(Long id) {
        return productRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + id));
    }

    /**
     * Batch-loads primary image URLs for a page of products.
     * Returns {@code Map<productId, imageUrl>}.
     */
    @Transactional(readOnly = true)
    public Map<Long, String> getPrimaryImageUrls(List<Long> productIds) {
        if (productIds.isEmpty()) return Map.of();
        return productImageRepository.findPrimaryImageDataByProductIds(productIds)
                .stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (String) row[1]
                ));
    }

    /**
     * Maps a {@link Product} entity to a {@link ProductDTO} (for pre-populating the edit form).
     * Must be called within an active transaction so lazy proxies can be accessed.
     */
    @Transactional(readOnly = true)
    public ProductDTO toDTO(Long id) {
        Product p = getProductByIdWithImages(id);
        return ProductDTO.builder()
                .id(p.getId())
                .name(p.getName())
                .slug(p.getSlug())
                .categoryId(p.getCategory() != null ? p.getCategory().getId() : null)
                .description(p.getDescription())
                .highlights(p.getHighlights())
                .boxBreakInfo(p.getBoxBreakInfo())
                .configuration(p.getConfiguration())
                .price(p.getPrice())
                .originalPrice(p.getOriginalPrice())
                .brand(p.getBrand())
                .isOnSale(p.getIsOnSale())
                .isNew(p.getIsNew())
                .isTrending(p.getIsTrending())
                .isBestSeller(p.getIsBestSeller())
                .isPreOrder(p.getIsPreOrder())
                .isFeatured(p.getIsFeatured())
                .sortOrder(p.getSortOrder())
                .isActive(p.getIsActive())
                .build();
    }

    // ── Public browse/detail queries ─────────────────────────────────────────

    /** Category browse page: active products for an L3 category, paginated. */
    @Transactional(readOnly = true)
    public Page<Product> getProductsByCategory(Long categoryId, Pageable pageable) {
        return productRepository.findByCategoryIdAndIsActiveTrue(categoryId, pageable);
    }

    /** Product detail page: load product by slug with images + category chain. */
    @Transactional(readOnly = true)
    public Optional<Product> getProductBySlugForDetail(String slug) {
        return productRepository.findBySlugAndIsActiveWithDetails(slug);
    }

    /** Related products: same L3 category, exclude current, active only. */
    @Transactional(readOnly = true)
    public List<Product> getRelatedProducts(Long categoryId, Long excludeId, int limit) {
        return productRepository.findByCategoryIdAndIsActiveTrueAndIdNot(
                categoryId, excludeId,
                PageRequest.of(0, limit, Sort.by("sortOrder").ascending())
        ).getContent();
    }

    // ── Public homepage queries ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Product> getTrendingProducts(int limit) {
        return productRepository.findByIsTrendingTrueAndIsActiveTrue(
                PageRequest.of(0, limit, Sort.by("sortOrder").ascending())).getContent();
    }

    @Transactional(readOnly = true)
    public List<Product> getNewProducts(int limit) {
        return productRepository.findByIsNewTrueAndIsActiveTrue(
                PageRequest.of(0, limit, Sort.by("sortOrder").ascending())).getContent();
    }

    @Transactional(readOnly = true)
    public List<Product> getBestSellerProducts(int limit) {
        return productRepository.findByIsBestSellerTrueAndIsActiveTrue(
                PageRequest.of(0, limit, Sort.by("sortOrder").ascending())).getContent();
    }

    @Transactional(readOnly = true)
    public List<Product> getOnSaleProducts(int limit) {
        return productRepository.findByIsOnSaleTrueAndIsActiveTrue(
                PageRequest.of(0, limit, Sort.by("sortOrder").ascending())).getContent();
    }

    @Transactional(readOnly = true)
    public List<Product> getFeaturedProducts(int limit) {
        return productRepository.findByIsFeaturedTrueAndIsActiveTrue(
                PageRequest.of(0, limit, Sort.by("sortOrder").ascending())).getContent();
    }

    /** Search page: active products matching name or description. */
    @Transactional(readOnly = true)
    public Page<Product> searchProducts(String q, Pageable pageable) {
        Specification<Product> spec = Specification
                .where(ProductSpecification.hasSearchQuery(q))
                .and(ProductSpecification.hasStatus("active"));
        return productRepository.findAll(spec, pageable);
    }

    /**
     * Public /products listing: always active, optionally filtered by flag.
     * filter values: "trending", "new", "best-sellers", "sale", "pre-order", or null/empty for all.
     */
    @Transactional(readOnly = true)
    public Page<Product> getPublicProductList(String filter, Pageable pageable) {
        // Base: isActive = true
        Specification<Product> spec = Specification.where(ProductSpecification.hasStatus("active"));
        // Optional flag filter
        String statusKey = switch (filter == null ? "" : filter) {
            case "trending"     -> "trending";
            case "new"          -> "new";
            case "best-sellers" -> "best_seller";
            case "sale"         -> "sale";
            case "pre-order"    -> "pre_order";
            default             -> null;
        };
        if (statusKey != null) {
            spec = spec.and(ProductSpecification.hasStatus(statusKey));
        }
        return productRepository.findAll(spec, pageable);
    }

    // ── Writes ────────────────────────────────────────────────────────────────

    public Product createProduct(ProductDTO dto, List<MultipartFile> images) {
        validateSlugUnique(dto.getSlug(), null);
        Category category = resolveCategory(dto.getCategoryId());

        Product product = applyDTO(new Product(), dto, category);
        product = productRepository.save(product);

        saveImages(product, images, true);
        return product;
    }

    public Product updateProduct(Long id, ProductDTO dto,
                                 List<MultipartFile> newImages,
                                 List<Long> deleteImageIds) {
        Product product = getProductByIdWithImages(id);
        validateSlugUnique(dto.getSlug(), id);
        Category category = resolveCategory(dto.getCategoryId());

        // ── delete requested images ──────────────────────────────────────────
        if (deleteImageIds != null && !deleteImageIds.isEmpty()) {
            product.getImages().removeIf(img -> {
                if (deleteImageIds.contains(img.getId())) {
                    fileUploadService.deleteFile(img.getImageUrl());
                    return true;   // orphanRemoval handles DB delete
                }
                return false;
            });
        }

        applyDTO(product, dto, category);
        product = productRepository.save(product);

        // ── upload new images ─────────────────────────────────────────────────
        boolean hasPrimary = product.getImages().stream()
                .anyMatch(img -> Boolean.TRUE.equals(img.getIsPrimary()));
        saveImages(product, newImages, !hasPrimary);

        return product;
    }

    @Transactional
    public void deleteProduct(Long id) {
        Product product = getProductByIdWithImages(id);
        inquiryRepository.deleteByProductId(id);
        product.getImages().forEach(img -> fileUploadService.deleteFile(img.getImageUrl()));
        productRepository.delete(product);
    }

    public void toggleActive(Long id) {
        Product p = getProductById(id);
        p.setIsActive(!Boolean.TRUE.equals(p.getIsActive()));
        productRepository.save(p);
    }

    public void toggleFlag(Long id, String flag) {
        Product p = getProductById(id);
        switch (flag) {
            case "isOnSale"    -> p.setIsOnSale(!Boolean.TRUE.equals(p.getIsOnSale()));
            case "isNew"       -> p.setIsNew(!Boolean.TRUE.equals(p.getIsNew()));
            case "isTrending"  -> p.setIsTrending(!Boolean.TRUE.equals(p.getIsTrending()));
            case "isBestSeller"-> p.setIsBestSeller(!Boolean.TRUE.equals(p.getIsBestSeller()));
            case "isPreOrder"  -> p.setIsPreOrder(!Boolean.TRUE.equals(p.getIsPreOrder()));
            case "isFeatured"  -> p.setIsFeatured(!Boolean.TRUE.equals(p.getIsFeatured()));
            default -> throw new IllegalArgumentException("Unknown product flag: " + flag);
        }
        productRepository.save(p);
    }

    public void setPrimaryImage(Long productId, Long imageId) {
        productImageRepository.findByProductIdOrderBySortOrderAsc(productId).forEach(img -> {
            img.setIsPrimary(img.getId().equals(imageId));
            productImageRepository.save(img);
        });
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void saveImages(Product product, List<MultipartFile> files, boolean firstAsPrimary) {
        if (files == null || files.isEmpty()) return;
        boolean setFirst = firstAsPrimary;
        int nextOrder = productImageRepository.findByProductIdOrderBySortOrderAsc(product.getId()).size();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            String url = fileUploadService.uploadFile(file, "products");
            productImageRepository.save(ProductImage.builder()
                    .product(product)
                    .imageUrl(url)
                    .sortOrder(nextOrder++)
                    .isPrimary(setFirst)
                    .build());
            setFirst = false;
        }
    }

    private Product applyDTO(Product p, ProductDTO dto, Category category) {
        p.setName(dto.getName());
        p.setSlug(dto.getSlug().trim());
        p.setDescription(dto.getDescription());
        p.setHighlights(dto.getHighlights());
        p.setBoxBreakInfo(dto.getBoxBreakInfo());
        p.setConfiguration(dto.getConfiguration());
        p.setPrice(dto.getPrice());
        p.setOriginalPrice(dto.getOriginalPrice());
        p.setBrand(dto.getBrand());
        p.setIsOnSale(Boolean.TRUE.equals(dto.getIsOnSale()));
        p.setIsNew(Boolean.TRUE.equals(dto.getIsNew()));
        p.setIsTrending(Boolean.TRUE.equals(dto.getIsTrending()));
        p.setIsBestSeller(Boolean.TRUE.equals(dto.getIsBestSeller()));
        p.setIsPreOrder(Boolean.TRUE.equals(dto.getIsPreOrder()));
        p.setIsFeatured(Boolean.TRUE.equals(dto.getIsFeatured()));
        p.setCategory(category);
        p.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);
        p.setIsActive(Boolean.TRUE.equals(dto.getIsActive()));
        return p;
    }

    private Category resolveCategory(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new IllegalArgumentException("Category not found: " + categoryId));
    }

    private void validateSlugUnique(String slug, Long excludeId) {
        productRepository.findBySlug(slug).ifPresent(existing -> {
            if (!existing.getId().equals(excludeId)) {
                throw new IllegalArgumentException("Slug \"" + slug + "\" is already in use.");
            }
        });
    }
}
