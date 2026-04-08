package com.cardshowcase.controller.admin;

import com.cardshowcase.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Lightweight JSON API consumed by admin-panel JavaScript (cascading dropdowns, etc.).
 * Secured by the existing {@code /admin/**} rule in SecurityConfig.
 */
@RestController
@RequestMapping("/admin/api")
@RequiredArgsConstructor
public class AdminApiController {

    private final CategoryRepository categoryRepository;

    /**
     * Returns the direct children of {@code parentId} as {@code [{id, name}]} JSON.
     * Used by the cascading L1 → L2 → L3 category dropdowns on the product form.
     */
    @GetMapping("/categories/children")
    public List<Map<String, Object>> categoryChildren(@RequestParam Long parentId) {
        return categoryRepository.findByParentIdOrderBySortOrderAsc(parentId)
                .stream()
                .map(c -> Map.<String, Object>of("id", c.getId(), "name", c.getName()))
                .toList();
    }
}
