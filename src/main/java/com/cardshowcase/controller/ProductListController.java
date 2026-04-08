package com.cardshowcase.controller;

import com.cardshowcase.model.dto.BreadcrumbItem;
import com.cardshowcase.model.entity.Product;
import com.cardshowcase.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductListController {

    private final ProductService productService;

    @GetMapping
    public String list(@RequestParam(name = "filter", required = false) String filter,
                       @RequestParam(name = "sort",   defaultValue = "default") String sort,
                       @RequestParam(name = "page",   defaultValue = "0") int page,
                       Model model) {

        Sort sortSpec = switch (sort) {
            case "price-asc"  -> Sort.by("price").ascending();
            case "price-desc" -> Sort.by("price").descending();
            case "newest"     -> Sort.by("createdAt").descending();
            default           -> Sort.by("sortOrder").ascending();
        };

        Pageable pageable = PageRequest.of(Math.max(0, page), 20, sortSpec);
        Page<Product> products = productService.getPublicProductList(filter, pageable);

        List<Long> ids = products.stream().map(Product::getId).toList();
        Map<Long, String> primaryImages = productService.getPrimaryImageUrls(ids);

        String filterTitle = switch (filter == null ? "" : filter) {
            case "trending"     -> "Trending Products";
            case "new"          -> "New Releases";
            case "best-sellers" -> "Best Sellers";
            case "sale"         -> "Deals & Specials";
            case "pre-order"    -> "Pre-Orders";
            default             -> "All Products";
        };

        model.addAttribute("products",     products);
        model.addAttribute("primaryImages", primaryImages);
        model.addAttribute("currentFilter", filter);
        model.addAttribute("currentSort",   sort);
        model.addAttribute("filterTitle",   filterTitle);
        model.addAttribute("breadcrumbs", List.of(
                new BreadcrumbItem("Home", "/"),
                new BreadcrumbItem(filterTitle, null)
        ));

        return "products";
    }
}
