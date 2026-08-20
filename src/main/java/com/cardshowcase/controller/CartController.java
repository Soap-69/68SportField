package com.cardshowcase.controller;

import com.cardshowcase.model.entity.Cart;
import com.cardshowcase.model.entity.CartItem;
import com.cardshowcase.service.CartService;
import com.cardshowcase.service.CustomerPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping("/cart")
    public String cart(@AuthenticationPrincipal CustomerPrincipal principal,
                       HttpServletRequest request,
                       HttpServletResponse response,
                       Model model) {
        Long customerId = principal != null ? principal.getId() : null;
        Cart cart = cartService.getOrCreateCart(request, response, customerId);

        List<String> warnings = cartService.validateAndAdjustCart(cart);
        List<CartItem> items = cartService.getItemsForCart(cart);

        model.addAttribute("cart", cart);
        model.addAttribute("items", items);
        model.addAttribute("cartSubtotal", cartService.getCartSubtotal(cart));
        model.addAttribute("cartItemCount", cartService.getCartItemCount(cart));
        model.addAttribute("warnings", warnings);
        return "cart";
    }
}
