package com.cardshowcase.config;

import com.cardshowcase.controller.CartController;
import com.cardshowcase.controller.CategoryController;
import com.cardshowcase.controller.CheckoutController;
import com.cardshowcase.controller.CustomerAccountController;
import com.cardshowcase.controller.CustomerAuthController;
import com.cardshowcase.controller.HomeController;
import com.cardshowcase.controller.InquiryController;
import com.cardshowcase.controller.ProductController;
import com.cardshowcase.controller.ProductListController;
import com.cardshowcase.controller.SearchController;
import com.cardshowcase.model.dto.CategoryTreeNode;
import com.cardshowcase.service.CartService;
import com.cardshowcase.service.CategoryService;
import com.cardshowcase.service.CustomerPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

/**
 * Injects category tree data and cart item count into every public (non-admin) controller's model.
 * Add new public controllers to {@code assignableTypes} as they are created.
 */
@ControllerAdvice(assignableTypes = {HomeController.class, CategoryController.class, ProductController.class, ProductListController.class, SearchController.class, InquiryController.class, CustomerAuthController.class, CustomerAccountController.class, CartController.class, CheckoutController.class})
@RequiredArgsConstructor
public class PublicModelAttributes {

    private final CategoryService categoryService;
    private final CartService cartService;

    @ModelAttribute("categoryTree")
    public List<CategoryTreeNode> categoryTree() {
        return categoryService.getActiveCategoryTree();
    }

    @ModelAttribute("cartItemCount")
    public int cartItemCount(HttpServletRequest request) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof CustomerPrincipal principal) {
                return cartService.getCartItemCountByCustomerId(principal.getId());
            }
            String token = cartService.getCartCookie(request);
            return cartService.getCartItemCountBySessionToken(token);
        } catch (Exception e) {
            return 0;
        }
    }
}
