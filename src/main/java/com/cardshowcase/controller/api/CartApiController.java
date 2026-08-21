package com.cardshowcase.controller.api;

import com.cardshowcase.model.entity.Cart;
import com.cardshowcase.service.CartService;
import com.cardshowcase.service.CustomerPrincipal;
import com.cardshowcase.exception.InsufficientStockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartApiController {

    private final CartService cartService;

    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> addToCart(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal CustomerPrincipal principal,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            Long variantId = Long.valueOf(body.get("variantId").toString());
            int quantity = Integer.parseInt(body.get("quantity").toString());
            if (quantity <= 0)
                return ResponseEntity.ok(Map.of("success", false, "message", "Quantity must be at least 1."));
            Long customerId = principal != null ? principal.getId() : null;
            Cart cart = cartService.getOrCreateCart(request, response, customerId);
            cart = cartService.addToCart(cart, variantId, quantity);
            int count = cartService.getCartItemCount(cart);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "cartItemCount", count,
                "message", "Added to cart"
            ));
        } catch (InsufficientStockException e) {
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "Unable to add item to cart."));
        }
    }

    @PostMapping("/update")
    public ResponseEntity<Map<String, Object>> updateItem(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal CustomerPrincipal principal,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            Long cartItemId = Long.valueOf(body.get("cartItemId").toString());
            int quantity = Integer.parseInt(body.get("quantity").toString());
            Long customerId = principal != null ? principal.getId() : null;
            Cart cart = cartService.getOrCreateCart(request, response, customerId);
            cart = cartService.updateItemQuantity(cart, cartItemId, quantity);
            int count = cartService.getCartItemCount(cart);
            BigDecimal subtotal = cartService.getCartSubtotal(cart);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "cartItemCount", count,
                "cartTotal", subtotal.toPlainString()
            ));
        } catch (InsufficientStockException e) {
            return ResponseEntity.ok(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "Unable to update cart."));
        }
    }

    @PostMapping("/remove")
    public ResponseEntity<Map<String, Object>> removeItem(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal CustomerPrincipal principal,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            Long cartItemId = Long.valueOf(body.get("cartItemId").toString());
            Long customerId = principal != null ? principal.getId() : null;
            Cart cart = cartService.getOrCreateCart(request, response, customerId);
            cart = cartService.removeItem(cart, cartItemId);
            int count = cartService.getCartItemCount(cart);
            BigDecimal total = cartService.getCartSubtotal(cart);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "cartItemCount", count,
                "cartTotal", total.toPlainString()
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "Unable to remove item."));
        }
    }

    @GetMapping("/count")
    public ResponseEntity<Map<String, Object>> getCount(
            @AuthenticationPrincipal CustomerPrincipal principal,
            HttpServletRequest request, HttpServletResponse response) {
        Long customerId = principal != null ? principal.getId() : null;
        String token = cartService.getCartCookie(request);
        int count = customerId != null
                ? cartService.getCartItemCountByCustomerId(customerId)
                : cartService.getCartItemCountBySessionToken(token);
        return ResponseEntity.ok(Map.of("count", count));
    }
}
