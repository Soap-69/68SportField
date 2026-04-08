package com.cardshowcase.repository;

import com.cardshowcase.model.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long>,
                                            JpaSpecificationExecutor<Product> {

    Optional<Product> findBySlug(String slug);

    long countByCategoryId(Long categoryId);

    /**
     * List query: eagerly loads the 3-level category chain (ManyToOne — safe with pagination).
     * Images are NOT fetched here to avoid the HHH90003004 in-memory pagination warning.
     */
    @Override
    @EntityGraph(attributePaths = {
        "category",
        "category.parent",
        "category.parent.parent"
    })
    Page<Product> findAll(Specification<Product> spec, Pageable pageable);

    /**
     * Detail query (edit form, delete): fetches images + full category chain in one SQL.
     * DISTINCT prevents duplicate rows from the collection join.
     */
    @Query("""
        SELECT DISTINCT p FROM Product p
        LEFT JOIN FETCH p.images
        LEFT JOIN FETCH p.category      c
        LEFT JOIN FETCH c.parent        cp
        LEFT JOIN FETCH cp.parent
        WHERE p.id = :id
        """)
    Optional<Product> findByIdWithDetails(@Param("id") Long id);

    /**
     * Public product detail: fetch by slug (active only) with images + full category chain.
     */
    @Query("""
        SELECT DISTINCT p FROM Product p
        LEFT JOIN FETCH p.images
        LEFT JOIN FETCH p.category      c
        LEFT JOIN FETCH c.parent        cp
        LEFT JOIN FETCH cp.parent
        WHERE p.slug = :slug AND p.isActive = true
        """)
    Optional<Product> findBySlugAndIsActiveWithDetails(@Param("slug") String slug);

    /** Related products: same category, exclude self, active only. */
    Page<Product> findByCategoryIdAndIsActiveTrueAndIdNot(Long categoryId, Long excludeId, Pageable pageable);

    // ── flag / status helpers ────────────────────────────────────────────────

    Page<Product> findByCategoryIdAndIsActive(Long categoryId, Boolean isActive, Pageable pageable);
    Page<Product> findByCategoryIdAndIsActiveTrue(Long categoryId, Pageable pageable);

    Page<Product> findByIsOnSaleTrue(Pageable pageable);
    Page<Product> findByIsOnSaleTrueAndIsActiveTrue(Pageable pageable);

    Page<Product> findByIsNewTrue(Pageable pageable);
    Page<Product> findByIsNewTrueAndIsActiveTrue(Pageable pageable);

    Page<Product> findByIsTrendingTrue(Pageable pageable);
    Page<Product> findByIsTrendingTrueAndIsActiveTrue(Pageable pageable);

    Page<Product> findByIsBestSellerTrue(Pageable pageable);
    Page<Product> findByIsBestSellerTrueAndIsActiveTrue(Pageable pageable);

    Page<Product> findByIsFeaturedTrueAndIsActiveTrue(Pageable pageable);

    Page<Product> findByNameContainingIgnoreCase(String name, Pageable pageable);
    Page<Product> findByNameContainingIgnoreCaseAndIsActiveTrue(String name, Pageable pageable);
}
