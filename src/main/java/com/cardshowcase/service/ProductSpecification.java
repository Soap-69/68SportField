package com.cardshowcase.service;

import com.cardshowcase.model.entity.Product;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

/**
 * Static JPA Specification factories for dynamic product filtering.
 * Compose with {@code Specification.where(...).and(...)} in the service layer.
 */
public final class ProductSpecification {

    private ProductSpecification() {}

    public static Specification<Product> hasNameLike(String search) {
        return (root, query, cb) ->
            StringUtils.hasText(search)
                ? cb.like(cb.lower(root.get("name")), "%" + search.toLowerCase() + "%")
                : null;
    }

    public static Specification<Product> hasCategoryId(Long categoryId) {
        return (root, query, cb) ->
            categoryId != null
                ? cb.equal(root.get("category").get("id"), categoryId)
                : null;
    }

    public static Specification<Product> hasSearchQuery(String query) {
        return (root, q, cb) -> {
            if (!StringUtils.hasText(query)) return null;
            String pattern = "%" + query.toLowerCase() + "%";
            return cb.or(
                cb.like(cb.lower(root.get("name")), pattern),
                cb.like(cb.lower(root.get("description")), pattern)
            );
        };
    }

    public static Specification<Product> hasStatus(String status) {
        if (!StringUtils.hasText(status)) return null;
        return switch (status) {
            case "active"      -> (root, q, cb) -> cb.isTrue(root.get("isActive"));
            case "inactive"    -> (root, q, cb) -> cb.isFalse(root.get("isActive"));
            case "sale"        -> (root, q, cb) -> cb.isTrue(root.get("isOnSale"));
            case "new"         -> (root, q, cb) -> cb.isTrue(root.get("isNew"));
            case "trending"    -> (root, q, cb) -> cb.isTrue(root.get("isTrending"));
            case "best_seller" -> (root, q, cb) -> cb.isTrue(root.get("isBestSeller"));
            case "pre_order"   -> (root, q, cb) -> cb.isTrue(root.get("isPreOrder"));
            default            -> null;
        };
    }
}
