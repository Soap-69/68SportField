package com.cardshowcase.controller;

import com.cardshowcase.model.dto.CarouselSlide;
import com.cardshowcase.model.entity.Banner;
import com.cardshowcase.model.entity.Product;
import com.cardshowcase.service.BannerService;
import com.cardshowcase.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class HomeController {

    private final ProductService productService;
    private final BannerService  bannerService;

    @GetMapping("/")
    public String home(Model model) {
        // ── Carousel slides: banners first, then featured products ──
        List<Banner>  banners         = bannerService.getActiveBanners();
        List<Product> featuredProducts = productService.getFeaturedProducts(6);

        List<CarouselSlide> carouselSlides = new ArrayList<>();
        for (Banner b : banners) {
            carouselSlides.add(new CarouselSlide(b.getImageUrl(), b.getTitle(), b.getLinkUrl()));
        }
        for (Product p : featuredProducts) {
            // Use primary image; will be resolved via primaryImages map if needed
            carouselSlides.add(new CarouselSlide(null, p.getName(),
                    "/products/" + p.getSlug()));
        }

        // Resolve primary images for featured products
        List<Long> featuredIds = featuredProducts.stream().map(Product::getId).toList();
        Map<Long, String> featuredImages = featuredIds.isEmpty()
                ? Map.of()
                : productService.getPrimaryImageUrls(featuredIds);

        // Re-build slides with correct image URLs
        carouselSlides.clear();
        for (Banner b : banners) {
            carouselSlides.add(new CarouselSlide(b.getImageUrl(), b.getTitle(), b.getLinkUrl()));
        }
        for (Product p : featuredProducts) {
            String imgUrl = featuredImages.get(p.getId());
            if (imgUrl != null) {
                carouselSlides.add(new CarouselSlide(imgUrl, p.getName(),
                        "/products/" + p.getSlug()));
            }
        }

        // ── Product sections ──
        List<Product> trending    = productService.getTrendingProducts(6);
        List<Product> newProducts = productService.getNewProducts(6);
        List<Product> bestSellers = productService.getBestSellerProducts(6);
        List<Product> onSale      = productService.getOnSaleProducts(6);

        List<Long> allIds = new ArrayList<>();
        trending.forEach(p -> allIds.add(p.getId()));
        newProducts.forEach(p -> allIds.add(p.getId()));
        bestSellers.forEach(p -> allIds.add(p.getId()));
        onSale.forEach(p -> allIds.add(p.getId()));

        Map<Long, String> primaryImages = allIds.isEmpty()
                ? Map.of()
                : productService.getPrimaryImageUrls(allIds);

        model.addAttribute("carouselSlides",      carouselSlides);
        model.addAttribute("banners",             banners);          // kept for backward compat
        model.addAttribute("trendingProducts",    trending);
        model.addAttribute("newProducts",         newProducts);
        model.addAttribute("bestSellerProducts",  bestSellers);
        model.addAttribute("saleProducts",        onSale);
        model.addAttribute("primaryImages",       primaryImages);
        return "index";
    }
}
