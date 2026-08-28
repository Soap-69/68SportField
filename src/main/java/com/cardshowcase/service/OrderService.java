package com.cardshowcase.service;

import com.cardshowcase.exception.IdempotencyConflictException;
import com.cardshowcase.exception.InsufficientStockException;
import com.cardshowcase.model.dto.CheckoutRequest;
import com.cardshowcase.model.entity.*;
import com.cardshowcase.repository.*;
import com.cardshowcase.shipping.ShippingQuote;
import com.cardshowcase.spec.OrderSpecification;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class OrderService {

    private final CartService cartService;
    private final CartItemRepository cartItemRepository;
    private final InventoryService inventoryService;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CustomerAddressRepository customerAddressRepository;
    private final ShippingService shippingService;
    private final ShipmentRepository shipmentRepository;

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
     *   Identity check (before fingerprint/content check):
     *   - Authenticated replay must match customer_id.
     *   - Guest replay must match the cart session token (cookie), not guestEmail.
     *
     *   Policy table:
     *     Key exists + wrong identity                    → 409 (cross-account/session leak prevention)
     *     Key exists + correct identity + cart empty     → genuine retry → return original order
     *     Key exists + correct identity + same fp        → concurrent dup → return original order
     *     Key exists + correct identity + diff fp        → key reuse conflict → 409
     *     Key is new                                     → create order normally
     *
     * Shipping: $0 placeholder — approved shipping rules not yet defined.
     * Do not invent a rate or threshold. Replace when rules are approved.
     */
    public Order checkout(CheckoutRequest req,
                          HttpServletRequest httpReq,
                          HttpServletResponse httpResp,
                          CustomerPrincipal principal) {

        Long customerId = principal != null ? principal.getId() : null;

        // Resolve and immediately hash the guest cart token. The raw cookie value is
        // never stored; only the SHA-256 digest is persisted and used for identity checks.
        String cartToken = customerId == null ? cartService.getCartCookie(httpReq) : null;
        String cartTokenHash = cartToken != null ? sha256Hex(cartToken) : null;

        // 1. Idempotency check — FIRST, before input validation and empty-cart guard
        Optional<Order> existing = orderRepository.findByIdempotencyKey(req.getIdempotencyKey());
        if (existing.isPresent()) {
            Order prev = existing.get();

            // Verify the replay comes from the same identity — prevents cross-account/session leakage
            verifyIdempotencyIdentity(prev, customerId, cartTokenHash, req.getIdempotencyKey());

            // Resolve cart to detect a conflict (may already be empty on genuine retry)
            List<CartItem> currentItems = cartService.findExistingCart(httpReq, customerId)
                    .map(c -> cartItemRepository.findByCartId(c.getId()))
                    .orElse(List.of());

            if (!currentItems.isEmpty()) {
                // Cart still has items: check fingerprint to detect key reuse with different content
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

        // 2. Validate checkout input (after idempotency so genuine replays are never blocked)
        validateCheckoutInput(req, customerId);

        // 3. Resolve cart — never from request body
        Cart cart = cartService.findExistingCart(httpReq, customerId)
                .orElseThrow(() -> new IllegalStateException("No active cart found."));

        List<CartItem> items = cartItemRepository.findByCartId(cart.getId());
        if (items.isEmpty()) {
            throw new IllegalStateException("Cart is empty.");
        }

        // 4. Authoritative item validation: variant + product must be active; stock sufficient.
        //    This is the definitive gate — do NOT rely on cart-page pre-validation only.
        List<String> stockErrors = new ArrayList<>();
        for (CartItem item : items) {
            ProductVariant v = item.getVariant();
            if (!Boolean.TRUE.equals(v.getIsActive())) {
                throw new IllegalStateException(
                        "\"" + v.getProduct().getName() + " (" + v.getVariantType() +
                        ")\" is no longer available.");
            }
            if (!Boolean.TRUE.equals(v.getProduct().getIsActive())) {
                throw new IllegalStateException(
                        "\"" + v.getProduct().getName() + "\" is no longer available.");
            }
            int stock = inventoryService.getTotalStock(v.getId());
            if (item.getQuantity() > stock) {
                stockErrors.add(String.format("\"%s (%s)\": requested %d, available %d",
                        v.getProduct().getName(), v.getVariantType(),
                        item.getQuantity(), stock));
            }
        }
        if (!stockErrors.isEmpty()) {
            throw new InsufficientStockException("Insufficient stock: " + String.join("; ", stockErrors));
        }

        // 5. Compute fingerprint (stored on order for future idempotency replay detection)
        String fingerprint = computeFingerprint(req, items, customerId);

        // 6. Resolve address snapshots
        String[] shippingSnap = resolveShippingSnapshot(req, customerId);
        // [0]=firstName [1]=lastName [2]=line1 [3]=line2 [4]=city [5]=state [6]=zip [7]=country [8]=phone
        String[] billingSnap  = resolveBillingSnapshot(req, customerId, shippingSnap);

        // 6b. Validate shipping destination: US-only, valid state code.
        //     Uses the single authoritative rule in ShippingService (shared with quote preview).
        //     shippingSnap[7]=country, shippingSnap[5]=state
        shippingService.validateDestination(shippingSnap[7], shippingSnap[5]);

        // 7. Calculate totals.
        // Tax: $0 placeholder — deferred to a later week.
        BigDecimal subtotal = items.stream()
                .map(i -> i.getVariant().getEffectivePrice()
                        .multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Shipping: apply frozen rules engine. AK/HI and REQUIRES_MANUAL_QUOTE both
        // leave shipping_amount at $0 — no invented price is stored at checkout time.
        ServiceLevel serviceLevel = req.getServiceLevel() != null
                ? req.getServiceLevel() : ServiceLevel.GROUND;
        ShippingQuote shippingQuote = shippingService.calculateQuote(
                nvl(shippingSnap[5]), serviceLevel, subtotal);
        BigDecimal shipping = shippingQuote.isResolved()
                ? shippingQuote.amount() : BigDecimal.ZERO;

        BigDecimal tax   = BigDecimal.ZERO; // deferred
        BigDecimal total = subtotal.add(shipping).add(tax);

        // 8. Persist order
        Order order = Order.builder()
                .orderNumber(generateOrderNumber())
                .idempotencyKey(req.getIdempotencyKey())
                .requestFingerprint(fingerprint)
                .customer(customerId != null ? em.getReference(Customer.class, customerId) : null)
                .guestCartTokenHash(cartTokenHash)
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

        // 9. Persist order items with snapshots
        for (CartItem item : items) {
            ProductVariant v = item.getVariant();
            BigDecimal unitPrice = v.getEffectivePrice();
            orderItemRepository.save(OrderItem.builder()
                    .order(order)
                    .productVariant(v)
                    .productName(v.getProduct().getName())
                    .skuSnapshot(v.getSku())
                    .variantTypeSnapshot(v.getVariantType())
                    .unitPrice(unitPrice)
                    .quantity(item.getQuantity())
                    .lineSubtotal(unitPrice.multiply(BigDecimal.valueOf(item.getQuantity())))
                    .build());
        }

        // 10. Clear cart
        cartService.clearCart(cart);

        // 11. Initialize Shipment for continental US orders at checkout time.
        //     AK/HI Shipments are created later, upon Order transitioning to PAID.
        if (!shippingQuote.isAkHiDeferred()) {
            shippingService.initializeShipmentForOrder(order, serviceLevel, shippingQuote);
        }

        log.info("Order created: {} (customer={}, guest={}, shippingQuote={})",
                order.getOrderNumber(), customerId, order.getGuestEmail(), shippingQuote.status());
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
        OrderStatus oldStatus = order.getStatus(); // capture before mutation
        order.setStatus(newStatus);
        Order saved = orderRepository.save(order);
        log.info("Order {} transitioned {} → {}", order.getOrderNumber(), oldStatus, newStatus);

        // AK/HI orders: create Shipment (QUOTE_REQUIRED) immediately upon becoming PAID.
        // Continental US Shipments are created at checkout time (OrderService.checkout).
        if (newStatus == OrderStatus.PAID
                && shippingService.isAkHi(order.getShippingState())) {
            shippingService.initializeAkHiShipment(saved);
        }

        return saved;
    }

    // ── Fulfillment gating ────────────────────────────────────────────

    /**
     * Returns true only when the order is fully ready for physical fulfillment.
     *
     * Conditions (all must be true):
     *   1. Order.status == PAID
     *   2. Shipment.shippingPaymentStatus is NOT_REQUIRED, PAID, or WAIVED
     *      (i.e. no outstanding supplemental shipping charge)
     *
     * Order=PAID alone is NOT sufficient for AK/HI orders — the supplemental
     * shipping charge must also be resolved before fulfillment can begin.
     *
     * This method has no callers or UI this week; it exists so future weeks
     * (fulfillment, admin dashboards) have a single source of truth, and
     * additional prerequisites can be added here without touching ShippingPaymentStatus.
     */
    @Transactional(readOnly = true)
    public boolean isReadyForFulfillment(Order order) {
        if (order.getStatus() != OrderStatus.PAID) {
            return false;
        }
        // Delegates to ShippingPaymentStatus.isFulfillmentReady() — the single source of truth.
        return shippingService.findByOrderId(order.getId())
                .map(s -> s.getShippingPaymentStatus().isFulfillmentReady())
                .orElse(false);
    }

    // ── Queries ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Order findById(Long orderId) {
        return orderRepository.findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
    }

    @Transactional(readOnly = true)
    public List<Order> findOrdersByCustomerId(Long customerId) {
        return orderRepository.findByCustomer_IdOrderByCreatedAtDesc(customerId);
    }

    /**
     * Returns the order only if it belongs to {@code customerId}.
     * Throws {@link SecurityException} (→ 403) if it doesn't.
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

    /**
     * Used by the controller to resolve the winning order after a near-simultaneous
     * duplicate submission is detected via {@code DataIntegrityViolationException}
     * on the idempotency key UNIQUE constraint.
     *
     * Applies the same identity verification as the normal replay path so that a
     * concurrent duplicate cannot expose another account's or session's order.
     */
    @Transactional(readOnly = true)
    public Optional<Order> findForIdempotentReplay(String idempotencyKey,
                                                    HttpServletRequest httpReq,
                                                    CustomerPrincipal principal) {
        Long customerId = principal != null ? principal.getId() : null;
        String cartToken = customerId == null ? cartService.getCartCookie(httpReq) : null;
        String cartTokenHash = cartToken != null ? sha256Hex(cartToken) : null;
        return orderRepository.findByIdempotencyKey(idempotencyKey).filter(order -> {
            try {
                verifyIdempotencyIdentity(order, customerId, cartTokenHash, idempotencyKey);
                return true;
            } catch (IdempotencyConflictException e) {
                return false;
            }
        });
    }

    // ── Admin order management ────────────────────────────────────────

    /**
     * Dispatches a shipment atomically: transitions Order PROCESSING→SHIPPED
     * and sets Shipment.shippedAt = now() in a SINGLE transaction.
     * Requires: Order.status == PROCESSING, Shipment.carrier != null, Shipment.trackingNumber != null.
     */
    @Transactional
    public Order dispatchShipment(Long orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        if (order.getStatus() != OrderStatus.PROCESSING) {
            throw new IllegalStateException("Cannot dispatch order " + orderId
                + ": must be in PROCESSING status, but is " + order.getStatus());
        }
        Shipment shipment = shipmentRepository.findByOrder_Id(orderId)
            .orElseThrow(() -> new IllegalStateException("No shipment found for order " + orderId));
        if (shipment.getCarrier() == null || shipment.getTrackingNumber() == null) {
            throw new IllegalStateException("Cannot dispatch order " + orderId
                + ": tracking must be recorded first (carrier and trackingNumber required).");
        }
        // Atomic: both changes in the same transaction
        order.setStatus(OrderStatus.SHIPPED);
        order = orderRepository.save(order);
        shipment.setShippedAt(java.time.LocalDateTime.now());
        shipmentRepository.save(shipment);
        log.info("Order {} dispatched: PROCESSING → SHIPPED, shippedAt recorded", order.getOrderNumber());
        return order;
    }

    /** Admin routine transitions (no approval needed). */
    @Transactional
    public Order markProcessing(Long orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        if (order.getStatus() != OrderStatus.PAID) {
            throw new IllegalStateException("Cannot mark order " + orderId
                + " as PROCESSING: must be in PAID status, but is " + order.getStatus());
        }
        if (!isReadyForFulfillment(order)) {
            throw new IllegalStateException(
                "Order is not ready for fulfillment: supplemental shipping obligation not yet resolved");
        }
        return transitionTo(order, OrderStatus.PROCESSING);
    }

    @Transactional
    public Order markDelivered(Long orderId) { return simpleTransition(orderId, OrderStatus.DELIVERED); }

    @Transactional
    public Order markCompleted(Long orderId) { return simpleTransition(orderId, OrderStatus.COMPLETED); }

    private Order simpleTransition(Long orderId, OrderStatus next) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        return transitionTo(order, next);
    }

    @Transactional(readOnly = true)
    public Page<Order> findOrders(String search, String status, LocalDate from, LocalDate to, Pageable pageable) {
        Specification<Order> spec = OrderSpecification.withFilters(search, status, from, to);
        return orderRepository.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public List<Order> findOrdersForExport(String search, String status, LocalDate from, LocalDate to) {
        Specification<Order> spec = OrderSpecification.withFilters(search, status, from, to);
        return orderRepository.findAll(spec);
    }

    // ── Idempotency identity verification ────────────────────────────

    /**
     * Verifies that the current caller is the same identity that originally submitted
     * this idempotency key, preventing cross-account and cross-session information leakage.
     *
     * <ul>
     *   <li>Authenticated: order's {@code customer_id} must equal the current {@code customerId}.</li>
     *   <li>Guest: the SHA-256 hash of the current cart-cookie token must match the stored
     *       {@code guest_cart_token_hash}. Identity is tied to the session token, NOT to
     *       {@code guestEmail}, because two different guests may share an email address.</li>
     * </ul>
     *
     * @param cartTokenHash SHA-256 hex digest of the current request's cart cookie
     *                      (null for authenticated callers)
     */
    private void verifyIdempotencyIdentity(Order existing, Long customerId, String cartTokenHash,
                                            String key) {
        if (customerId != null) {
            // Authenticated caller: order must belong to this customer
            if (existing.getCustomer() == null ||
                    !existing.getCustomer().getId().equals(customerId)) {
                throw new IdempotencyConflictException(
                        "Idempotency key '" + key + "' was already used by a different account.");
            }
        } else {
            // Guest caller: compare hashes — raw token is never stored or compared directly
            if (!Objects.equals(existing.getGuestCartTokenHash(), cartTokenHash)) {
                throw new IdempotencyConflictException(
                        "Idempotency key '" + key + "' was already used by a different session.");
            }
        }
    }

    // ── Checkout input validation ─────────────────────────────────────

    /**
     * Validates checkout request fields conditionally by caller identity.
     * Must be called AFTER the idempotency check so that genuine retries
     * (which may have an empty or missing cart) are never blocked by input validation.
     */
    private void validateCheckoutInput(CheckoutRequest req, Long customerId) {
        if (customerId == null) {
            // Guest checkout: contact info + shipping address required
            require(req.getGuestName(), "Guest name is required.");
            requireEmail(req.getGuestEmail());
            requireShippingFields(req);
        } else {
            // Authenticated checkout: saved address OR complete inline fields
            if (req.getSavedShippingAddressId() == null) {
                requireShippingFields(req);
            }
        }
        // Billing (applies to both guest and authenticated)
        if (!req.isBillingSameAsShipping() && req.getSavedBillingAddressId() == null) {
            requireBillingFields(req);
        }
    }

    private static void requireShippingFields(CheckoutRequest req) {
        require(req.getShippingFirstName(),    "Shipping first name is required.");
        require(req.getShippingLastName(),     "Shipping last name is required.");
        require(req.getShippingAddressLine1(), "Shipping address line 1 is required.");
        require(req.getShippingCity(),         "Shipping city is required.");
        require(req.getShippingState(),        "Shipping state is required.");
        require(req.getShippingZip(),          "Shipping ZIP is required.");
        require(req.getShippingCountry(),      "Shipping country is required.");
    }

    private static void requireBillingFields(CheckoutRequest req) {
        require(req.getBillingFirstName(),    "Billing first name is required.");
        require(req.getBillingLastName(),     "Billing last name is required.");
        require(req.getBillingAddressLine1(), "Billing address line 1 is required.");
        require(req.getBillingCity(),         "Billing city is required.");
        require(req.getBillingState(),        "Billing state is required.");
        require(req.getBillingZip(),          "Billing ZIP is required.");
        require(req.getBillingCountry(),      "Billing country is required.");
    }

    private static void require(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
    }

    private static void requireEmail(String email) {
        if (email == null || email.isBlank() || !email.contains("@") || !email.contains(".")) {
            throw new IllegalArgumentException("A valid guest email address is required.");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /**
     * Generates a human-readable order number with high collision resistance.
     *
     * Format: {@code ORD-YYYYMMDD-XXXXXXXX} where the suffix is 8 base-36 characters
     * (digits 0–9 and uppercase A–Z), giving ~2.8 trillion possible values per day.
     * This replaces the prior 6-digit decimal suffix (~1 million/day) which was
     * statistically likely to collide at moderate order volumes.
     *
     * The {@code order_number} column has a UNIQUE constraint, so any improbable
     * collision causes a retry via the normal idempotency flow.
     */
    private String generateOrderNumber() {
        String date   = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String suffix = randomBase36(8);
        return "ORD-" + date + "-" + suffix;
    }

    /** Returns a random uppercase base-36 string of the given length. */
    private static String randomBase36(int length) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int n = rng.nextInt(36);
            sb.append(n < 10 ? (char) ('0' + n) : (char) ('A' + n - 10));
        }
        return sb.toString();
    }

    /**
     * Fingerprint = SHA-256 of:
     *   cart item lines + customer identity + full shipping address (or saved ID)
     *   + billing address (or saved ID, or "same-as-ship") + serviceLevel.
     *
     * For inline addresses all nine persisted fields are included so that any change
     * to the snapshot (name, line2, phone, etc.) is detected as a conflict.
     * Saved-address flows fingerprint the saved address identifier only.
     * Including serviceLevel ensures GROUND vs NEXT_DAY_AIR with the same key
     * and the same cart is treated as a conflict (different financial outcome).
     * Stable across identical retries; changes if any input dimension changes.
     */
    private String computeFingerprint(CheckoutRequest req, List<CartItem> items, Long customerId) {
        String cartPart = items.stream()
                .sorted(Comparator.comparing(i -> i.getVariant().getId()))
                .map(i -> i.getVariant().getId() + ":" + i.getQuantity())
                .collect(Collectors.joining(","));

        String identityPart = customerId != null
                ? "customer:" + customerId
                : "guest:" + req.getGuestEmail();

        String shipPart = req.getSavedShippingAddressId() != null
                ? "saved-ship:" + req.getSavedShippingAddressId()
                : inlineAddressFp(
                        req.getShippingFirstName(),    req.getShippingLastName(),
                        req.getShippingAddressLine1(), req.getShippingAddressLine2(),
                        req.getShippingCity(),          req.getShippingState(),
                        req.getShippingZip(),           req.getShippingCountry(),
                        req.getShippingPhone());

        String billPart = req.isBillingSameAsShipping()
                ? "same-as-ship"
                : req.getSavedBillingAddressId() != null
                        ? "saved-bill:" + req.getSavedBillingAddressId()
                        : inlineAddressFp(
                                req.getBillingFirstName(),    req.getBillingLastName(),
                                req.getBillingAddressLine1(), req.getBillingAddressLine2(),
                                req.getBillingCity(),          req.getBillingState(),
                                req.getBillingZip(),           req.getBillingCountry(),
                                req.getBillingPhone());

        String slPart = req.getServiceLevel() != null ? req.getServiceLevel().name() : "GROUND";

        return sha256Hex(cartPart + "||" + identityPart + "||" + shipPart + "||" + billPart + "||" + slPart);
    }

    /**
     * Canonical string representation of an inline address for fingerprinting.
     * All nine fields that are persisted in the Order snapshot are included so
     * that any material change (including name or phone) is detected as a conflict.
     */
    private static String inlineAddressFp(
            String firstName, String lastName,
            String line1,     String line2,
            String city,      String state,
            String zip,       String country,
            String phone) {
        return nvl(firstName) + "|" + nvl(lastName)  + "|"
             + nvl(line1)     + "|" + nvl(line2)      + "|"
             + nvl(city)      + "|" + nvl(state)       + "|"
             + nvl(zip)       + "|" + nvl(country)     + "|"
             + nvl(phone);
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
}
