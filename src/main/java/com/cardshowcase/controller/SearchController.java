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
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {

    private final ProductService productService;

    @GetMapping
    public String search(@RequestParam(name = "q", defaultValue = "") String q,
                         @RequestParam(name = "page", defaultValue = "0") int page,
                         @RequestParam(name = "sort", defaultValue = "relevance") String sort,
                         Model model) {

        Sort sortSpec = switch (sort) {
            case "price-asc"  -> Sort.by("price").ascending();
            case "price-desc" -> Sort.by("price").descending();
            case "newest"     -> Sort.by("createdAt").descending();
            default           -> Sort.by("sortOrder").ascending();
        };

        Pageable pageable = PageRequest.of(Math.max(0, page), 24, sortSpec);
        Page<Product> results = productService.searchProducts(q, pageable);

        List<Long> productIds = results.stream().map(Product::getId).toList();
        Map<Long, String> primaryImages = productService.getPrimaryImageUrls(productIds);

        model.addAttribute("results", results);
        model.addAttribute("primaryImages", primaryImages);
        model.addAttribute("q", q);
        model.addAttribute("sort", sort);
        model.addAttribute("breadcrumbs", List.of(
                new BreadcrumbItem("Home", "/"),
                new BreadcrumbItem("Search", null)
        ));

        return "search";
    }
}
