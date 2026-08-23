package com.cardshowcase.controller;

import com.cardshowcase.model.entity.Cart;
import com.cardshowcase.model.entity.CartItem;
import com.cardshowcase.model.entity.CustomerAddress;
import com.cardshowcase.service.CartService;
import com.cardshowcase.service.CustomerAddressService;
import com.cardshowcase.service.CustomerPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.math.BigDecimal;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class CheckoutController {

    // Shipping rules are not yet approved. Display $0 as a placeholder until defined.

    private final CartService cartService;
    private final CustomerAddressService addressService;

    /**
     * GET /checkout — gateway page.
     * Authenticated customers are immediately redirected to /checkout/auth
     * so they never see the "Sign In / Continue as Guest" prompt.
     */
    @GetMapping("/checkout")
    public String checkout(@AuthenticationPrincipal CustomerPrincipal principal) {
        if (principal != null) {
            return "redirect:/checkout/auth";
        }
        return "checkout";
    }

    /**
     * GET /checkout/auth — full checkout form for logged-in customers.
     * Loads saved addresses and the current cart summary into the model.
     * Redirects to /cart if the cart is empty.
     */
    @GetMapping("/checkout/auth")
    public String authCheckout(@AuthenticationPrincipal CustomerPrincipal principal,
                               HttpServletRequest request, Model model) {
        if (principal == null) {
            return "redirect:/checkout";
        }

        Cart cart = cartService.findExistingCart(request, principal.getId())
                .filter(c -> cartService.getCartItemCount(c) > 0)
                .orElse(null);
        if (cart == null) {
            return "redirect:/cart";
        }

        List<CartItem>       items     = cartService.getItemsForCart(cart);
        List<CustomerAddress> addresses = addressService.getAddressesByCustomer(principal.getId());
        BigDecimal subtotal  = cartService.getCartSubtotal(cart);
        BigDecimal shipping  = BigDecimal.ZERO; // deferred: approved shipping rules not yet defined
        BigDecimal tax       = BigDecimal.ZERO; // deferred
        BigDecimal total     = subtotal.add(shipping).add(tax);

        model.addAttribute("cartItems",      items);
        model.addAttribute("addresses",      addresses);
        model.addAttribute("subtotal",       subtotal);
        model.addAttribute("shippingAmount", shipping);
        model.addAttribute("taxAmount",      tax);
        model.addAttribute("orderTotal",     total);

        return "checkout-auth";
    }

    /**
     * GET /checkout/guest — guest checkout form.
     * Authenticated users are bounced back to /checkout (which redirects them to /checkout/auth).
     * Guests with an empty cart are sent to /cart first.
     */
    @GetMapping("/checkout/guest")
    public String guestCheckout(@AuthenticationPrincipal CustomerPrincipal principal,
                                HttpServletRequest request) {
        if (principal != null) {
            return "redirect:/checkout";
        }
        boolean hasItems = cartService.findExistingCart(request, null)
                .map(cart -> cartService.getCartItemCount(cart) > 0)
                .orElse(false);
        if (!hasItems) {
            return "redirect:/cart";
        }
        return "checkout-guest";
    }
}
