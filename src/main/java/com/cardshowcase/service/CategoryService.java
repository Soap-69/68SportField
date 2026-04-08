package com.cardshowcase.service;

import com.cardshowcase.model.dto.CategoryForm;
import com.cardshowcase.model.dto.CategoryOption;
import com.cardshowcase.model.dto.CategoryTreeNode;
import com.cardshowcase.model.entity.Category;
import com.cardshowcase.repository.CategoryRepository;
import com.cardshowcase.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Transactional
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository  productRepository;

    // ── Reads ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Category findById(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Category not found: " + id));
    }

    @Transactional(readOnly = true)
    public Optional<Category> findBySlug(String slug) {
        return categoryRepository.findBySlug(slug);
    }

    /**
     * Returns all categories flattened in tree order (L1 → its L2 children → their L3 children)
     * without touching lazy-loaded associations.
     */
    @Transactional(readOnly = true)
    public List<Category> getCategoryTreeFlat() {
        List<Category> roots = categoryRepository.findByParentIsNullOrderBySortOrderAsc();
        List<Category> result = new ArrayList<>();
        for (Category root : roots) {
            result.add(root);
            List<Category> l2List = categoryRepository.findByParentIdOrderBySortOrderAsc(root.getId());
            for (Category l2 : l2List) {
                result.add(l2);
                result.addAll(categoryRepository.findByParentIdOrderBySortOrderAsc(l2.getId()));
            }
        }
        return result;
    }

    /**
     * Returns L1 and L2 categories for the parent dropdown (tree-ordered, L3 excluded).
     */
    @Transactional(readOnly = true)
    public List<Category> getParentOptions() {
        List<Category> roots = categoryRepository.findByParentIsNullOrderBySortOrderAsc();
        List<Category> result = new ArrayList<>();
        for (Category root : roots) {
            result.add(root);
            result.addAll(categoryRepository.findByParentIdOrderBySortOrderAsc(root.getId()));
        }
        return result;
    }

    /**
     * Like {@link #getParentOptions()} but also excludes {@code excludeId} and all its descendants
     * (used on the edit form to prevent circular parent references).
     */
    @Transactional(readOnly = true)
    public List<Category> getParentOptionsExcluding(Long excludeId) {
        Set<Long> excluded = new HashSet<>(getDescendantIds(excludeId));
        excluded.add(excludeId);
        return getParentOptions().stream()
                .filter(c -> !excluded.contains(c.getId()))
                .toList();
    }

    /**
     * Maps a Category entity to a pre-populated form DTO for the edit page.
     */
    @Transactional(readOnly = true)
    public CategoryForm toForm(Long id) {
        Category cat = findById(id);
        return CategoryForm.builder()
                .id(cat.getId())
                .parentId(cat.getParent() != null ? cat.getParent().getId() : null)
                .name(cat.getName())
                .slug(cat.getSlug())
                .imageUrl(cat.getImageUrl())
                .sortOrder(cat.getSortOrder())
                .isActive(cat.getIsActive())
                .build();
    }

    // ── Product-form helpers ──────────────────────────────────────────────────

    /** All L1 (root) categories — for the top cascade dropdown. */
    @Transactional(readOnly = true)
    public List<Category> getL1Categories() {
        return categoryRepository.findByParentIsNullOrderBySortOrderAsc();
    }

    /** Direct children of {@code parentId} — used by the AJAX cascade endpoint. */
    @Transactional(readOnly = true)
    public List<Category> getChildrenOf(Long parentId) {
        if (parentId == null) return Collections.emptyList();
        return categoryRepository.findByParentIdOrderBySortOrderAsc(parentId);
    }

    /**
     * All active L3 categories as {@code "L1 › L2 › L3"} options.
     * Parents are eagerly fetched in one query (no N+1).
     */
    @Transactional(readOnly = true)
    public List<CategoryOption> getL3WithPath() {
        return categoryRepository.findAllL3WithParentsFetched()
                .stream()
                .map(c -> new CategoryOption(c.getId(), buildPath(c)))
                .toList();
    }

    /** Builds "L1 › L2 › L3" by traversing the parent chain (parents must be loaded). */
    public String buildPath(Category c) {
        LinkedList<String> parts = new LinkedList<>();
        Category cur = c;
        while (cur != null) {
            parts.addFirst(cur.getName());
            cur = cur.getParent();
        }
        return String.join(" › ", parts);
    }

    // ── Public-facing helpers ─────────────────────────────────────────────────

    /** Active children of a given category (used for L1→L2 and L2→L3 browse pages). */
    @Transactional(readOnly = true)
    public List<Category> getActiveChildrenOf(Long parentId) {
        return categoryRepository.findByParentIdAndIsActiveOrderBySortOrderAsc(parentId, true);
    }

    /** Active L1 (root) categories for public navigation. */
    @Transactional(readOnly = true)
    public List<Category> getActiveL1Categories() {
        return categoryRepository.findByLevelAndIsActiveOrderBySortOrderAsc(1, true);
    }

    /**
     * Builds the full active category tree (L1 → L2 → L3) for the mega-menu.
     * Uses 1 + N(L1) + N(L2) queries — acceptable for a small, stable tree.
     */
    @Transactional(readOnly = true)
    public List<CategoryTreeNode> getActiveCategoryTree() {
        List<Category> l1s = categoryRepository.findByLevelAndIsActiveOrderBySortOrderAsc(1, true);
        List<CategoryTreeNode> tree = new ArrayList<>();
        for (Category l1 : l1s) {
            List<Category> l2s = categoryRepository
                    .findByParentIdAndIsActiveOrderBySortOrderAsc(l1.getId(), true);
            List<CategoryTreeNode> l2Nodes = new ArrayList<>();
            for (Category l2 : l2s) {
                List<Category> l3s = categoryRepository
                        .findByParentIdAndIsActiveOrderBySortOrderAsc(l2.getId(), true);
                List<CategoryTreeNode> l3Nodes = l3s.stream()
                        .map(l3 -> new CategoryTreeNode(l3, List.of()))
                        .toList();
                l2Nodes.add(new CategoryTreeNode(l2, l3Nodes));
            }
            tree.add(new CategoryTreeNode(l1, l2Nodes));
        }
        return tree;
    }

    // ── Writes ────────────────────────────────────────────────────────────────

    public void createCategory(CategoryForm form) {
        validateSlugUnique(form.getSlug(), null);

        Category parent = resolveParent(form.getParentId());
        int level = (parent == null) ? 1 : parent.getLevel() + 1;

        Category cat = Category.builder()
                .parent(parent)
                .level(level)
                .name(form.getName())
                .slug(form.getSlug())
                .imageUrl(trimToNull(form.getImageUrl()))
                .sortOrder(form.getSortOrder() != null ? form.getSortOrder() : 0)
                .isActive(form.getIsActive() != null ? form.getIsActive() : true)
                .build();

        categoryRepository.save(cat);
    }

    public void updateCategory(Long id, CategoryForm form) {
        Category cat = findById(id);
        validateSlugUnique(form.getSlug(), id);
        validateNoCircularParent(id, form.getParentId());

        Category parent = resolveParent(form.getParentId());
        int level = (parent == null) ? 1 : parent.getLevel() + 1;

        cat.setParent(parent);
        cat.setLevel(level);
        cat.setName(form.getName());
        cat.setSlug(form.getSlug());
        cat.setImageUrl(trimToNull(form.getImageUrl()));
        cat.setSortOrder(form.getSortOrder() != null ? form.getSortOrder() : 0);
        cat.setIsActive(form.getIsActive() != null ? form.getIsActive() : true);

        categoryRepository.save(cat);
    }

    public void deleteCategory(Long id) {
        Category cat = findById(id);

        long childCount = categoryRepository.countByParentId(id);
        if (childCount > 0) {
            throw new IllegalStateException(
                    "Cannot delete \"" + cat.getName() + "\": it has " + childCount + " child categor"
                    + (childCount == 1 ? "y" : "ies") + ". Remove them first.");
        }

        long productCount = productRepository.countByCategoryId(id);
        if (productCount > 0) {
            throw new IllegalStateException(
                    "Cannot delete \"" + cat.getName() + "\": it has " + productCount + " associated product"
                    + (productCount == 1 ? "" : "s") + ". Reassign or delete them first.");
        }

        categoryRepository.delete(cat);
    }

    public void toggleActive(Long id) {
        Category cat = findById(id);
        cat.setIsActive(!Boolean.TRUE.equals(cat.getIsActive()));
        categoryRepository.save(cat);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Category resolveParent(Long parentId) {
        if (parentId == null) return null;
        Category parent = categoryRepository.findById(parentId)
                .orElseThrow(() -> new IllegalArgumentException("Parent category not found: " + parentId));
        if (parent.getLevel() >= 3) {
            throw new IllegalArgumentException("An L3 category cannot be used as a parent.");
        }
        return parent;
    }

    private void validateSlugUnique(String slug, Long excludeId) {
        categoryRepository.findBySlug(slug).ifPresent(existing -> {
            if (!existing.getId().equals(excludeId)) {
                throw new IllegalArgumentException("Slug \"" + slug + "\" is already in use.");
            }
        });
    }

    private void validateNoCircularParent(Long categoryId, Long newParentId) {
        if (newParentId == null) return;
        if (newParentId.equals(categoryId)) {
            throw new IllegalStateException("A category cannot be its own parent.");
        }
        if (getDescendantIds(categoryId).contains(newParentId)) {
            throw new IllegalStateException("Cannot set a descendant as the parent — that would create a circular reference.");
        }
    }

    private Set<Long> getDescendantIds(Long categoryId) {
        Set<Long> descendants = new HashSet<>();
        Queue<Long> queue = new ArrayDeque<>();
        queue.add(categoryId);
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            for (Category child : categoryRepository.findByParentIdOrderBySortOrderAsc(current)) {
                descendants.add(child.getId());
                queue.add(child.getId());
            }
        }
        return descendants;
    }

    private static String trimToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
