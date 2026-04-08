package com.cardshowcase.repository;

import com.cardshowcase.model.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findBySlug(String slug);

    // ── tree navigation ───────────────────────────────────────────────────────
    List<Category> findByParentIsNullOrderBySortOrderAsc();

    List<Category> findByParentIdOrderBySortOrderAsc(Long parentId);

    long countByParentId(Long parentId);

    // ── filtered by active status ─────────────────────────────────────────────
    List<Category> findByParentIdAndIsActiveOrderBySortOrderAsc(Long parentId, Boolean isActive);

    List<Category> findByLevelAndIsActive(Integer level, Boolean isActive);

    List<Category> findByLevelAndIsActiveOrderBySortOrderAsc(Integer level, Boolean isActive);

    /**
     * Fetches all active L3 categories with L2 and L1 parents eagerly loaded
     * (used to build "L1 › L2 › L3" path labels without N+1 queries).
     */
    @Query("""
        SELECT c FROM Category c
        LEFT JOIN FETCH c.parent p
        LEFT JOIN FETCH p.parent
        WHERE c.level = 3 AND c.isActive = true
        ORDER BY c.sortOrder ASC
        """)
    List<Category> findAllL3WithParentsFetched();
}
