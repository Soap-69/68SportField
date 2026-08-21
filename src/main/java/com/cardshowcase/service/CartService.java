package com.cardshowcase.service;

import com.cardshowcase.exception.InsufficientStockException;
import com.cardshowcase.model.entity.*;
import com.cardshowcase.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CartService {

    public static final String COOKIE_NAME = "cart_token";
    private static final int COOKIE_MAX_AGE = 30 * 24 * 60 * 60; // 30 days

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductVariantRepository variantRepository;
    private final InventoryService inventoryService;

    @PersistenceContext
    private EntityManager em;

    // ── Cart Resolution ──────────────────────────────────────────

    public Cart getOrCreateCart(HttpServletRequest request, HttpServletResponse response,
                                 Long customerId) {
        if (customerId != null) {
            return cartRepository.findByCustomerId(customerId)
                    .orElseGet(() -> createCustomerCart(customerId));
        }
        // Guest: use cookie
        String token = getCartCookie(request);
        if (token != null) {
            Optional<Cart> existing = cartRepository.findBySessionToken(token);
            if (existing.isPresent()) return existing.get();
        }
        // Create new guest cart
        String newToken = UUID.randomUUID().toString();
        Cart cart = Cart.builder().sessionToken(newToken).build();
        cart = cartRepository.save(cart);
        setCartCookie(response, newToken);
        return cart;
    }

    private Cart createCustomerCart(Long customerId) {
        Cart cart = Cart.builder()
                .customer(em.getReference(Customer.class, customerId))
                .build();
        return cartRepository.save(cart);
    }

    // ── Cart Operations ──────────────────────────────────────────

    public Cart addToCart(Cart cart, Long variantId, int quantity) {
        ProductVariant variant = variantRepository.findById(variantId)
                .orElseThrow(() -> new IllegalArgumentException("Variant not found: " + variantId));

        if (!Boolean.TRUE.equals(variant.getIsActive()))
            throw new IllegalArgumentException("This product variant is no longer available.");
        if (variant.getProduct() == null || !Boolean.TRUE.equals(variant.getProduct().getIsActive()))
            throw new IllegalArgumentException("This product is no longer available.");

        // Validate stock only — inventory is NOT deducted here.
        // Deduction happens at checkout when the order is confirmed.
        int stock = inventoryService.getTotalStock(variantId);
        Optional<CartItem> existing = cartItemRepository.findByCartIdAndVariantId(cart.getId(), variantId);

        int newQuantity = existing.map(CartItem::getQuantity).orElse(0) + quantity;
        if (newQuantity > stock) {
            String msg = stock == 0
                    ? "This item is out of stock."
                    : "Only " + stock + " available" + (existing.isPresent() ? " (" + existing.get().getQuantity() + " already in cart)" : "") + ".";
            throw new InsufficientStockException(msg);
        }

        CartItem item = existing.orElseGet(() -> {
            CartItem ci = CartItem.builder().cart(cart).variant(variant).quantity(0).build();
            return cartItemRepository.save(ci);
        });
        item.setQuantity(newQuantity);
        cartItemRepository.save(item);
        return cartRepository.findById(cart.getId()).orElse(cart);
    }

    public Cart updateItemQuantity(Cart cart, Long cartItemId, int newQuantity) {
        CartItem item = cartItemRepository.findById(cartItemId)
                .filter(ci -> ci.getCart().getId().equals(cart.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Cart item not found"));

        if (newQuantity <= 0) {
            cartItemRepository.delete(item);
        } else {
            int stock = inventoryService.getTotalStock(item.getVariant().getId());
            if (newQuantity > stock)
                throw new InsufficientStockException("Only " + stock + " available.");
            item.setQuantity(newQuantity);
            cartItemRepository.save(item);
        }
        return cartRepository.findById(cart.getId()).orElse(cart);
    }

    public Cart removeItem(Cart cart, Long cartItemId) {
        cartItemRepository.findById(cartItemId)
                .filter(ci -> ci.getCart().getId().equals(cart.getId()))
                .ifPresent(cartItemRepository::delete);
        return cartRepository.findById(cart.getId()).orElse(cart);
    }

    public void clearCart(Cart cart) {
        cartItemRepository.deleteByCartId(cart.getId());
    }

    public int getCartItemCount(Cart cart) {
        if (cart == null) return 0;
        return cartItemRepository.findByCartId(cart.getId())
                .stream().mapToInt(CartItem::getQuantity).sum();
    }

    public BigDecimal getCartSubtotal(Cart cart) {
        if (cart == null) return BigDecimal.ZERO;
        return cartItemRepository.findByCartId(cart.getId())
                .stream().map(CartItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional(readOnly = true)
    public List<CartItem> getItemsForCart(Cart cart) {
        if (cart == null) return List.of();
        return cartItemRepository.findByCartId(cart.getId());
    }

    // ── Cart Merge on Login ───────────────────────────────────────

    public void mergeGuestCartOnLogin(String sessionToken, Long customerId) {
        Optional<Cart> guestOpt = cartRepository.findBySessionToken(sessionToken);
        if (guestOpt.isEmpty()) return;
        Cart guestCart = guestOpt.get();

        Cart customerCart = cartRepository.findByCustomerId(customerId)
                .orElseGet(() -> createCustomerCart(customerId));

        for (CartItem guestItem : cartItemRepository.findByCartId(guestCart.getId())) {
            Optional<CartItem> existing = cartItemRepository.findByCartIdAndVariantId(
                    customerCart.getId(), guestItem.getVariant().getId());
            if (existing.isPresent()) {
                CartItem ci = existing.get();
                ci.setQuantity(Math.max(ci.getQuantity(), guestItem.getQuantity()));
                cartItemRepository.save(ci);
            } else {
                CartItem newItem = CartItem.builder()
                        .cart(customerCart)
                        .variant(guestItem.getVariant())
                        .quantity(guestItem.getQuantity())
                        .build();
                cartItemRepository.save(newItem);
            }
        }
        cartRepository.delete(guestCart);
    }

    // ── Cart Item Count for navbar (read-only) ────────────────────

    @Transactional(readOnly = true)
    public int getCartItemCountByCustomerId(Long customerId) {
        return cartRepository.findByCustomerId(customerId)
                .map(this::getCartItemCount).orElse(0);
    }

    @Transactional(readOnly = true)
    public int getCartItemCountBySessionToken(String sessionToken) {
        if (sessionToken == null) return 0;
        return cartRepository.findBySessionToken(sessionToken)
                .map(this::getCartItemCount).orElse(0);
    }

    // ── Stock validation on cart page load ────────────────────────

    @Transactional
    public List<String> validateAndAdjustCart(Cart cart) {
        List<String> warnings = new ArrayList<>();
        for (CartItem item : cartItemRepository.findByCartId(cart.getId())) {
            ProductVariant variant = item.getVariant();
            if (!Boolean.TRUE.equals(variant.getIsActive()) ||
                    !Boolean.TRUE.equals(variant.getProduct().getIsActive())) {
                warnings.add("\"" + variant.getProduct().getName() + " (" + variant.getVariantType() + ")\" was removed (no longer available).");
                cartItemRepository.delete(item);
                continue;
            }
            int stock = inventoryService.getTotalStock(variant.getId());
            if (stock == 0) {
                warnings.add("\"" + variant.getProduct().getName() + " (" + variant.getVariantType() + ")\" is out of stock and was removed.");
                cartItemRepository.delete(item);
            } else if (item.getQuantity() > stock) {
                warnings.add("\"" + variant.getProduct().getName() + " (" + variant.getVariantType() + ")\" quantity adjusted to " + stock + " (only " + stock + " available).");
                item.setQuantity(stock);
                cartItemRepository.save(item);
            }
        }
        return warnings;
    }

    // ── Cookie helpers ────────────────────────────────────────────

    public String getCartCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst().orElse(null);
    }

    public void setCartCookie(HttpServletResponse response, String token) {
        Cookie cookie = new Cookie(COOKIE_NAME, token);
        cookie.setMaxAge(COOKIE_MAX_AGE);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
    }

    public void deleteCartCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setMaxAge(0);
        cookie.setPath("/");
        response.addCookie(cookie);
    }
}
