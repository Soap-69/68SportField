package com.cardshowcase.controller;

import com.cardshowcase.model.dto.BreadcrumbItem;
import com.cardshowcase.model.entity.Category;
import com.cardshowcase.model.entity.Product;
import com.cardshowcase.service.CategoryService;
import com.cardshowcase.service.ProductService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.*;

@Controller
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;
    private final ProductService  productService;

    @GetMapping("/category/{slug}")
    public String category(@PathVariable String slug,
                           @RequestParam(defaultValue = "0")  int page,
                           @RequestParam(defaultValue = "20") int size,
                           Model model,
                           HttpServletResponse response) {

        Optional<Category> optCat = categoryService.findBySlug(slug);
        if (optCat.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        }

        Category category = optCat.get();
        List<BreadcrumbItem> breadcrumbs = buildBreadcrumbs(category);

        model.addAttribute("category",    category);
        model.addAttribute("breadcrumbs", breadcrumbs);

        if (category.getLevel() < 3) {
            // L1 or L2 — show active subcategory children
            List<Category> children = categoryService.getActiveChildrenOf(category.getId());
            model.addAttribute("children", children);
            model.addAttribute("products", null);

        } else {
            // L3 — show paginated product listing
            Pageable pageable = PageRequest.of(page, size,
                    Sort.by("sortOrder").ascending().and(Sort.by("name").ascending()));
            Page<Product> products = productService.getProductsByCategory(category.getId(), pageable);

            List<Long> ids = products.getContent().stream().map(Product::getId).toList();
            Map<Long, String> primaryImages = productService.getPrimaryImageUrls(ids);

            model.addAttribute("children",      null);
            model.addAttribute("products",      products);
            model.addAttribute("primaryImages", primaryImages);
        }

        return "category";
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Builds a breadcrumb trail from root to the given category.
     * The last item (current category) has a null URL — rendered as plain text.
     * Lazy parent access is safe here because open-in-view keeps the session open.
     */
    private List<BreadcrumbItem> buildBreadcrumbs(Category category) {
        LinkedList<BreadcrumbItem> crumbs = new LinkedList<>();
        Category cur    = category;
        boolean current = true;
        while (cur != null) {
            String url = current ? null : "/category/" + cur.getSlug();
            crumbs.addFirst(new BreadcrumbItem(cur.getName(), url));
            cur     = cur.getParent();
            current = false;
        }
        crumbs.addFirst(new BreadcrumbItem("Home", "/"));
        return crumbs;
    }
}
