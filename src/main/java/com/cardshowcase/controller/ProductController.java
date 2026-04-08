package com.cardshowcase.controller;

import com.cardshowcase.model.dto.BreadcrumbItem;
import com.cardshowcase.model.entity.Category;
import com.cardshowcase.model.entity.Product;
import com.cardshowcase.model.entity.ProductImage;
import com.cardshowcase.service.ProductService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.*;

@Controller
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping("/product/{slug}")
    public String product(@PathVariable String slug,
                          Model model,
                          HttpServletResponse response) {

        Optional<Product> optProduct = productService.getProductBySlugForDetail(slug);
        if (optProduct.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        }

        Product product = optProduct.get();

        // Sort images: primary first, then by sortOrder
        List<ProductImage> images = product.getImages().stream()
                .sorted(Comparator
                        .<ProductImage, Boolean>comparing(img -> !Boolean.TRUE.equals(img.getIsPrimary()))
                        .thenComparing(ProductImage::getSortOrder))
                .toList();

        // Breadcrumbs: Home > L1 > L2 > L3 > Product Name
        List<BreadcrumbItem> breadcrumbs = buildProductBreadcrumbs(product);

        // Related products (same L3 category, active, max 4, exclude self)
        Long categoryId = product.getCategory() != null ? product.getCategory().getId() : null;
        List<Product> relatedProducts = categoryId != null
                ? productService.getRelatedProducts(categoryId, product.getId(), 4)
                : List.of();

        Map<Long, String> relatedImages = relatedProducts.isEmpty() ? Map.of()
                : productService.getPrimaryImageUrls(
                        relatedProducts.stream().map(Product::getId).toList());

        model.addAttribute("product",         product);
        model.addAttribute("images",          images);
        model.addAttribute("breadcrumbs",     breadcrumbs);
        model.addAttribute("relatedProducts", relatedProducts);
        model.addAttribute("relatedImages",   relatedImages);

        return "product";
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<BreadcrumbItem> buildProductBreadcrumbs(Product product) {
        LinkedList<BreadcrumbItem> crumbs = new LinkedList<>();
        // Current page — no link
        crumbs.addFirst(new BreadcrumbItem(product.getName(), null));

        Category cat = product.getCategory();
        while (cat != null) {
            crumbs.addFirst(new BreadcrumbItem(cat.getName(), "/category/" + cat.getSlug()));
            cat = cat.getParent();
        }
        crumbs.addFirst(new BreadcrumbItem("Home", "/"));
        return crumbs;
    }
}
