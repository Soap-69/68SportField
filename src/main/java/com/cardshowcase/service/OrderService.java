package com.cardshowcase.service;

import com.cardshowcase.exception.IdempotencyConflictException;
import com.cardshowcase.exception.InsufficientStockException;
import com.cardshowcase.model.dto.CheckoutRequest;
import com.cardshowcase.model.entity.*;
import com.cardshowcase.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class OrderService {

    private static final BigDecimal FREE_SHIPPING_THRESHOLD = new BigDecimal("100.00");
    private static final BigDecimal FLAT_SHIPPING_RATE      = new BigDecimal("9.99");

    private final CartService cartService;
    private final CartItemRepository cartItemRepository;
    private final InventoryService inventoryService;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CustomerAddressRepository customerAddressRepository;

    @PersistenceContext
    private EntityManager em;

    // ── Checkout ─────────────────────────────────────────────────────

    /**
     * Converts the caller's resolved cart into a PENDING_PAYMENT order.
     *
     * Idempotency policy (key checked BEFORE the empty-cart guard so genuine retries work):
     *
     *   Genuine retry scenario: client sent checkout, server succeeded, network timed out,
     *   client retries with the same key. At this point the cart is already cleared (empty).
     *   We must return the original order WITHOUT requiring the cart to still have items.
     *
     *   Conflict scenario: a different checkout attempt reuses an already-consumed key
     *   but the current cart has different items or the address differs → 409.
     *
     *   Policy table:
     *     Key exists + cart is empty              → genuine retry → return original order
     *     Key exists + cart has items + same fp   → duplicate in-flight → return original order
     *     Key exists + cart has items + diff fp   → key reuse conflict → 409
     *     Key is new                              → create order normally
     *
     * The cart is always resolved from the current request's session/cookie or the
     * authenticated customer's own cart. No cart_id is accepted from the client.
     */
    public Order checkout(CheckoutRequest req,
                          HttpServletRequest httpReq,
                          HttpServletResponse httpResp,
                          CustomerPrincipal principal) {

        Long customerId = principal != null ? principal.getId() : null;

        // 1. Idempotency check — FIRST, before cart/empty-cart guard
        Optional<Order> existing = orderRepository.findByIdempotencyKey(req.getIdempotencyKey());
        if (existing.isPresent()) {
            Order prev = existing.get();
            // Resolve cart to detect a conflict (may already be empty on genuine retry)
            List<CartItem> currentItems = cartService.findExistingCart(httpReq, customerId)
                    .map(c -> cartItemRepository.findByCartId(c.getId()))
                    .orElse(List.of());

            if (!currentItems.isEmpty()) {
                // Cart still has items: check fingerprint to detect key reuse with diff content
                String fp = computeFingerprint(req, currentItems, customerId);
                if (!fp.equals(prev.getRequestFingerprint())) {
                    throw new IdempotencyConflictException(
                            "Idempotency key '" + req.getIdempotencyKey() +
                            "' was already used with different request content.");
                }
            }
            // Empty cart = genuine retry after cart was cleared; matching fp = concurrent dup
            log.info("Idempotent replay for key={} → returning order {}",
                    req.getIdempotencyKey(), prev.getOrderNumber());
            return prev;
        }

        // 2. Resolve cart — never from request body
        Cart cart = cartService.findExistingCart(httpReq, customerId)
                .orElseThrow(() -> new IllegalStateException("No active cart found."));

        List<CartItem> items = cartItemRepository.findByCartId(cart.getId());
        if (items.isEmpty()) {
            throw new IllegalStateException("Cart is empty.");
        }

        // 3. Stock sufficiency check — read-only, no deduction
        List<String> stockErrors = new ArrayList<>();
        for (CartItem item : items) {
            int stock = inventoryService.getTotalStock(item.getVariant().getId());
            if (item.getQuantity() > stock) {
                stockErrors.add(String.format("\"%s (%s)\": requested %d, available %d",
                        item.getVariant().getProduct().getName(),
                        item.getVariant().getVariantType(),
                        item.getQuantity(), stock));
            }
        }
        if (!stockErrors.isEmpty()) {
            throw new InsufficientStockException("Insufficient stock: " + String.join("; ", stockErrors));
        }

        // 5. Compute fingerprint (stored on order for future idempotency replay detection)
        String fingerprint = computeFingerprint(req, items, customerId);

        // 6. Resolve shipping address snapshot
        String[] shippingSnap = resolveShippingSnapshot(req, customerId);
        // [0]=firstName [1]=lastName [2]=line1 [3]=line2 [4]=city [5]=state [6]=zip [7]=country [8]=phone

        // 7. Resolve billing address snapshot
        String[] billingSnap  = resolveBillingSnapshot(req, customerId, shippingSnap);

        // 8. Calculate totals
        BigDecimal subtotal = items.stream()
                .map(i -> i.getVariant().getEffectivePrice()
                        .multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal shipping = subtotal.compareTo(FREE_SHIPPING_THRESHOLD) >= 0
                ? BigDecimal.ZERO : FLAT_SHIPPING_RATE;
        BigDecimal tax   = BigDecimal.ZERO;   // tax calculation deferred
        BigDecimal total = subtotal.add(shipping).add(tax);

        // 8. Persist order
        Order order = Order.builder()
                .orderNumber(generateOrderNumber())
                .idempotencyKey(req.getIdempotencyKey())
                .requestFingerprint(fingerprint)
                .customer(customerId != null ? em.getReference(Customer.class, customerId) : null)
                .status(OrderStatus.PENDING_PAYMENT)
                .guestName(customerId == null ? req.getGuestName() : null)
                .guestEmail(customerId == null ? req.getGuestEmail() : null)
                // shipping snapshot
                .shippingFirstName(shippingSnap[0])
                .shippingLastName(shippingSnap[1])
                .shippingAddressLine1(shippingSnap[2])
                .shippingAddressLine2(shippingSnap[3])
                .shippingCity(shippingSnap[4])
                .shippingState(shippingSnap[5])
                .shippingZip(shippingSnap[6])
                .shippingCountry(shippingSnap[7])
                .shippingPhone(shippingSnap[8])
                // billing snapshot
                .billingFirstName(billingSnap[0])
                .billingLastName(billingSnap[1])
                .billingAddressLine1(billingSnap[2])
                .billingAddressLine2(billingSnap[3])
                .billingCity(billingSnap[4])
                .billingState(billingSnap[5])
                .billingZip(billingSnap[6])
                .billingCountry(billingSnap[7])
                .billingPhone(billingSnap[8])
                // totals
                .subtotal(subtotal)
                .shippingAmount(shipping)
                .taxAmount(tax)
                .total(total)
                .build();

        order = orderRepository.save(order);

        // 8. Persist order items with snapshots
        for (CartItem item : items) {
            ProductVariant v = item.getVariant();
            BigDecimal unitPrice = v.getEffectivePrice();
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(item.getQuantity()));
            OrderItem oi = OrderItem.builder()
                    .order(order)
                    .productVariant(v)
                    .productName(v.getProduct().getName())
                    .skuSnapshot(v.getSku())
                    .variantTypeSnapshot(v.getVariantType())
                    .unitPrice(unitPrice)
                    .quantity(item.getQuantity())
                    .lineSubtotal(lineTotal)
                    .build();
            orderItemRepository.save(oi);
        }

        // 9. Clear cart
        cartService.clearCart(cart);

        log.info("Order created: {} (customer={}, guest={})",
                order.getOrderNumber(), customerId, order.getGuestEmail());
        return order;
    }

    // ── State machine ─────────────────────────────────────────────────

    /**
     * Transitions an order to {@code newStatus}, enforcing legal state machine
     * transitions. Throws {@link IllegalStateException} on illegal transitions.
     */
    public Order transitionTo(Order order, OrderStatus newStatus) {
        if (!order.getStatus().canTransitionTo(newStatus)) {
            throw new IllegalStateException(
                    "Cannot transition order " + order.getOrderNumber() +
                    " from " + order.getStatus() + " to " + newStatus + ".");
        }
        order.setStatus(newStatus);
        Order saved = orderRepository.save(order);
        log.info("Order {} transitioned {} → {}", order.getOrderNumber(), order.getStatus(), newStatus);
        return saved;
    }

    // ── Queries ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Order> findOrdersByCustomerId(Long customerId) {
        return orderRepository.findByCustomer_IdOrderByCreatedAtDesc(customerId);
    }

    /**
     * Returns the order only if it belongs to {@code customerId}; throws 403-mapped
     * IllegalArgumentException otherwise.
     */
    @Transactional(readOnly = true)
    public Order findByIdForCustomer(Long orderId, Long customerId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        if (order.getCustomer() == null ||
                !order.getCustomer().getId().equals(customerId)) {
            throw new SecurityException("Access denied to order " + orderId);
        }
        return order;
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private String generateOrderNumber() {
        String date   = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String suffix = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        return "ORD-" + date + "-" + suffix;
    }

    /**
     * Fingerprint = SHA-256 of cart item lines + customer identity + shipping key fields.
     * Stable across identical retries; changes if cart content, address, or identity changes.
     */
    private String computeFingerprint(CheckoutRequest req, List<CartItem> items, Long customerId) {
        String cartPart = items.stream()
                .sorted(Comparator.comparing(i -> i.getVariant().getId()))
                .map(i -> i.getVariant().getId() + ":" + i.getQuantity())
                .collect(Collectors.joining(","));

        String identityPart = customerId != null
                ? "customer:" + customerId
                : "guest:" + req.getGuestEmail();

        String addressPart = nvl(req.getShippingAddressLine1()) + "|"
                + nvl(req.getShippingCity()) + "|"
                + nvl(req.getShippingZip());

        String raw = cartPart + "||" + identityPart + "||" + addressPart;
        return sha256Hex(raw);
    }

    private static String nvl(String s) { return s != null ? s.trim() : ""; }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Returns [firstName, lastName, line1, line2, city, state, zip, country, phone].
     * For logged-in customers with savedShippingAddressId, resolves and snapshots that address.
     * Otherwise uses inline fields from the request.
     */
    private String[] resolveShippingSnapshot(CheckoutRequest req, Long customerId) {
        if (customerId != null && req.getSavedShippingAddressId() != null) {
            CustomerAddress addr = customerAddressRepository
                    .findById(req.getSavedShippingAddressId())
                    .filter(a -> a.getCustomer().getId().equals(customerId))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Shipping address not found or does not belong to this account."));
            return new String[]{
                    addr.getFirstName(), addr.getLastName(),
                    addr.getAddressLine1(), addr.getAddressLine2(),
                    addr.getCity(), addr.getState(), addr.getZipCode(),
                    addr.getCountry(), addr.getPhone()};
        }
        return new String[]{
                req.getShippingFirstName(), req.getShippingLastName(),
                req.getShippingAddressLine1(), req.getShippingAddressLine2(),
                req.getShippingCity(), req.getShippingState(), req.getShippingZip(),
                req.getShippingCountry(), req.getShippingPhone()};
    }

    private String[] resolveBillingSnapshot(CheckoutRequest req, Long customerId,
                                            String[] shippingSnap) {
        if (req.isBillingSameAsShipping()) {
            return shippingSnap.clone();
        }
        if (customerId != null && req.getSavedBillingAddressId() != null) {
            CustomerAddress addr = customerAddressRepository
                    .findById(req.getSavedBillingAddressId())
                    .filter(a -> a.getCustomer().getId().equals(customerId))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Billing address not found or does not belong to this account."));
            return new String[]{
                    addr.getFirstName(), addr.getLastName(),
                    addr.getAddressLine1(), addr.getAddressLine2(),
                    addr.getCity(), addr.getState(), addr.getZipCode(),
                    addr.getCountry(), addr.getPhone()};
        }
        return new String[]{
                req.getBillingFirstName(), req.getBillingLastName(),
                req.getBillingAddressLine1(), req.getBillingAddressLine2(),
                req.getBillingCity(), req.getBillingState(), req.getBillingZip(),
                req.getBillingCountry(), req.getBillingPhone()};
    }
}
