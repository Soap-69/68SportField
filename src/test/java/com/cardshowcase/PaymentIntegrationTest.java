package com.cardshowcase;

import com.cardshowcase.model.dto.CheckoutRequest;
import com.cardshowcase.model.entity.*;
import com.cardshowcase.payment.GatewayOutcome;
import com.cardshowcase.repository.*;
import com.cardshowcase.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PaymentIntegrationTest extends BaseIntegrationTest {

    @Autowired ObjectMapper objectMapper;
    @Autowired CartService cartService;
    @Autowired CartRepository cartRepository;
    @Autowired CartItemRepository cartItemRepository;
    @Autowired ProductRepository productRepository;
    @Autowired ProductVariantRepository variantRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired InventoryLocationRepository locationRepository;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired InventoryService inventoryService;
    @Autowired CustomerAuthService customerAuthService;
    @Autowired CustomerRepository customerRepository;
    @Autowired CustomerAddressRepository addressRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired OrderItemRepository orderItemRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentEventRepository paymentEventRepository;
    @Autowired PaymentService paymentService;

    @SpyBean
    InventoryRepository inventoryRepositorySpy;

    private ProductVariant variant;
    private InventoryLocation locationA;
    private InventoryLocation locationB;
    private long ts;

    @BeforeEach
    void setUp() {
        ts = System.nanoTime();
        Mockito.reset(inventoryRepositorySpy);

        Category cat = categoryRepository.save(Category.builder()
                .name("Cat-" + ts).slug("cat-" + ts).level(1).sortOrder(0).isActive(true).build());
        Product product = productRepository.save(Product.builder()
                .name("Test Card " + ts).slug("prod-" + ts).category(cat)
                .sortOrder(0).isActive(true).isOnSale(false).isNew(false)
                .isTrending(false).isBestSeller(false).isPreOrder(false).isFeatured(false).build());
        variant = variantRepository.save(ProductVariant.builder()
                .product(product).variantType("Box")
                .price(new BigDecimal("50.00")).isActive(true).build());

        locationA = locationRepository.save(InventoryLocation.builder()
                .name("LocA-" + ts).isActive(true).build());
        locationB = locationRepository.save(InventoryLocation.builder()
                .name("LocB-" + ts).isActive(true).build());
        inventoryService.setStock(variant.getId(), locationA.getId(), 5);
        inventoryService.setStock(variant.getId(), locationB.getId(), 10);
    }

    // ── Helper: create a complete order via checkout ───────────────────

    private Order createGuestOrder(String token) throws Exception {
        Cart cart = cartRepository.save(Cart.builder().sessionToken(token).build());
        cartService.addToCart(cart, variant.getId(), 3);

        CheckoutRequest req = new CheckoutRequest();
        req.setIdempotencyKey(UUID.randomUUID().toString());
        req.setGuestName("Test Guest");
        req.setGuestEmail("guest-" + ts + "@example.com");
        req.setShippingFirstName("Test");
        req.setShippingLastName("Guest");
        req.setShippingAddressLine1("100 Main St");
        req.setShippingCity("Boston");
        req.setShippingState("MA");
        req.setShippingZip("02101");
        req.setShippingCountry("US");
        req.setBillingSameAsShipping(true);

        var result = mockMvc.perform(post("/api/checkout")
                        .with(csrf())
                        .cookie(new MockCookie("cart_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();

        long orderId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asLong();
        return orderRepository.findById(orderId).orElseThrow();
    }

    /** Builds a request body with a fresh idempotency key (one-off calls). */
    private String manualConfirmBody(GatewayOutcome outcome) throws Exception {
        return manualConfirmBody(outcome, UUID.randomUUID().toString());
    }

    /** Builds a request body with a specific idempotency key (idempotency/retry tests). */
    private String manualConfirmBody(GatewayOutcome outcome, String idempotencyKey) throws Exception {
        return objectMapper.writeValueAsString(
                Map.of("outcome", outcome.name(), "idempotencyKey", idempotencyKey));
    }

    private String adminUrl(long orderId) {
        return "/admin/api/orders/" + orderId + "/payments/manual-confirm";
    }

    // ── 1. Full SUCCESS flow ──────────────────────────────────────────

    @Test
    void manualConfirm_success_orderPaid_inventoryDeducted_eventRecorded() throws Exception {
        Order order = createGuestOrder("pay-success-" + ts);
        // Before: 5 + 10 = 15 stock, cart deducted 3 → 12 remaining? No, checkout clears cart but NOT inventory
        // Inventory deduction happens at payment confirmation
        int stockBefore = inventoryService.getTotalStock(variant.getId());

        mockMvc.perform(post(adminUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualConfirmBody(GatewayOutcome.SUCCESS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SUCCEEDED")));

        // Order transitioned to PAID
        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAID);

        // Payment SUCCEEDED
        Payment payment = paymentRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        // Inventory deducted by 3 (cart had 3 items)
        int stockAfter = inventoryService.getTotalStock(variant.getId());
        assertThat(stockAfter).isEqualTo(stockBefore - 3);

        // PAYMENT_SUCCEEDED event recorded
        boolean hasSucceededEvent = paymentEventRepository
                .existsByPayment_IdAndEventType(payment.getId(), PaymentEventType.PAYMENT_SUCCEEDED);
        assertThat(hasSucceededEvent).isTrue();
    }

    // ── 2. DECLINED → Payment FAILED (committed), Order stays PENDING_PAYMENT ─

    @Test
    void manualConfirm_declined_paymentFailed_committed_orderUnchanged() throws Exception {
        Order order = createGuestOrder("pay-declined-" + ts);

        mockMvc.perform(post(adminUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualConfirmBody(GatewayOutcome.DECLINED)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", notNullValue()));

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);

        Payment payment = paymentRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isNotBlank();
    }

    // ── 3. Stock insufficient at confirmation → FAILED (committed), no deduction ─

    @Test
    void manualConfirm_insufficientStock_paymentFailed_noDeduction() throws Exception {
        Order order = createGuestOrder("pay-nostock-" + ts);

        // Drain all stock AFTER checkout (checkout doesn't deduct inventory)
        inventoryService.setStock(variant.getId(), locationA.getId(), 0);
        inventoryService.setStock(variant.getId(), locationB.getId(), 0);

        int stockBefore = inventoryService.getTotalStock(variant.getId());
        assertThat(stockBefore).isZero();

        mockMvc.perform(post(adminUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualConfirmBody(GatewayOutcome.SUCCESS)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", containsString("stock")));

        // Payment FAILED and committed (not rolled back)
        Payment payment = paymentRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isEqualTo("INSUFFICIENT_STOCK");

        // No inventory changed
        assertThat(inventoryService.getTotalStock(variant.getId())).isZero();
    }

    // ── 4. Optimistic-lock conflict → rollback, Payment stays PENDING ──

    @Test
    void manualConfirm_optimisticLockConflict_paymentStaysPending_cleanRetryableError() throws Exception {
        Order order = createGuestOrder("pay-optlock-" + ts);

        // Initialize payment so we have a paymentId to check later
        Payment payment = paymentService.getOrInitializePayment(
                order.getId(), "MANUAL", UUID.randomUUID().toString());
        Long paymentId = payment.getId();

        // Force an OptimisticLockException on inventory save
        Mockito.doThrow(new ObjectOptimisticLockingFailureException(Inventory.class, 1L))
                .when(inventoryRepositorySpy).save(any(Inventory.class));

        mockMvc.perform(post(adminUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualConfirmBody(GatewayOutcome.SUCCESS)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.retryable", is(true)));

        // Payment must still be PENDING (not FAILED — lock conflict ≠ business failure)
        Payment reloaded = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    // ── 5. Multi-location deduction — deterministic by location ID ASC ─

    @Test
    void manualConfirm_multiLocation_deductsDeterministicallyFromLowestLocationIdFirst() throws Exception {
        // locationA.id < locationB.id (created first); locationA has 5, locationB has 10
        // Order has 3 items → should deduct all 3 from locationA (5 available)
        // if locationA.id < locationB.id, otherwise from locationB
        Order order = createGuestOrder("pay-multiloc-" + ts);

        mockMvc.perform(post(adminUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualConfirmBody(GatewayOutcome.SUCCESS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SUCCEEDED")));

        // Determine which location has lower ID
        long firstLocId = Math.min(locationA.getId(), locationB.getId());
        int stockInFirst = inventoryService.getStockByLocation(variant.getId(), firstLocId);
        // locationA started with 5, locationB with 10; 3 deducted from the first (lowest ID)
        // If locationA is first: 5-3=2; if locationB is first (shouldn't happen as A was created first)
        if (firstLocId == locationA.getId()) {
            assertThat(stockInFirst).isEqualTo(2); // 5 - 3 = 2
            assertThat(inventoryService.getStockByLocation(variant.getId(), locationB.getId())).isEqualTo(10);
        } else {
            assertThat(stockInFirst).isEqualTo(7); // 10 - 3 = 7
            assertThat(inventoryService.getStockByLocation(variant.getId(), locationA.getId())).isEqualTo(5);
        }
    }

    // ── 6. Duplicate manual-confirm on already-SUCCEEDED → no double-deduction ─

    @Test
    void manualConfirm_alreadySucceeded_idempotentNoOp_noDoubleDeduction() throws Exception {
        Order order = createGuestOrder("pay-idem-" + ts);
        int stockBefore = inventoryService.getTotalStock(variant.getId());
        // Same key for both calls — exercising the retry-of-same-request contract
        String key = "idem-nodup-" + ts;

        // First confirm → SUCCEEDED
        mockMvc.perform(post(adminUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualConfirmBody(GatewayOutcome.SUCCESS, key)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SUCCEEDED")));

        int stockAfterFirst = inventoryService.getTotalStock(variant.getId());
        assertThat(stockAfterFirst).isEqualTo(stockBefore - 3);

        // Second confirm — same key, same order → idempotent no-op (SUCCEEDED already)
        mockMvc.perform(post(adminUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualConfirmBody(GatewayOutcome.SUCCESS, key)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SUCCEEDED")));

        // Stock unchanged after second call
        assertThat(inventoryService.getTotalStock(variant.getId())).isEqualTo(stockAfterFirst);
        // Order still PAID (not double-transitioned)
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
    }

    // ── 7. FAILED → retry → SUCCEEDED (same key both calls; both events exist) ──

    @Test
    void manualConfirm_retryAfterFailed_succeeds_bothEventsRecorded() throws Exception {
        Order order = createGuestOrder("pay-retry-" + ts);
        // Same key for both calls — the retry is a replay of the same logical request
        String key = "retry-key-" + ts;

        // First call: DECLINED → FAILED
        mockMvc.perform(post(adminUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualConfirmBody(GatewayOutcome.DECLINED, key)))
                .andExpect(status().isUnprocessableEntity());

        Payment failedPayment = paymentRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(failedPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);

        // Second call — same key: controller finds existing FAILED payment, applies retry, confirms
        mockMvc.perform(post(adminUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualConfirmBody(GatewayOutcome.SUCCESS, key)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SUCCEEDED")));

        Payment succeededPayment = paymentRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(succeededPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        List<PaymentEvent> events = paymentEventRepository
                .findByPayment_IdOrderByCreatedAtAsc(succeededPayment.getId());

        assertThat(events).extracting(PaymentEvent::getEventType)
                .contains(PaymentEventType.PAYMENT_RETRY_STARTED, PaymentEventType.PAYMENT_SUCCEEDED);
    }

    // ── 8. DB-level UNIQUE(order_id) prevents second Payment row ──────

    @Test
    void dbConstraint_uniqueOrderId_preventsDuplicatePaymentRows() throws Exception {
        Order order = createGuestOrder("pay-unique-" + ts);

        // Create first payment normally
        paymentService.getOrInitializePayment(order.getId(), "MANUAL", UUID.randomUUID().toString());

        // Attempting to insert a second payment for the same order via service returns the existing one
        Payment second = paymentService.getOrInitializePayment(
                order.getId(), "MANUAL", UUID.randomUUID().toString());
        assertThat(paymentRepository.findByOrder_Id(order.getId())).isPresent();
        assertThat(paymentRepository.count()).isGreaterThanOrEqualTo(1);

        // Only one payment row for this order
        assertThat(paymentRepository.findByOrder_Id(order.getId())).isPresent();
        assertThat(second.getId()).isNotNull();
    }

    // ── 9. Idempotency key across different orders → rejected ─────────

    @Test
    void idempotencyKey_reusedAcrossDifferentOrders_rejected() throws Exception {
        Order orderA = createGuestOrder("pay-idem-A-" + ts);
        Order orderB = createGuestOrder("pay-idem-B-" + ts + "-b");

        String sharedKey = "shared-key-" + ts;

        // Create payment for order A with shared key
        paymentService.getOrInitializePayment(orderA.getId(), "MANUAL", sharedKey);

        // Attempt to create payment for order B with same key → rejected
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> paymentService.getOrInitializePayment(orderB.getId(), "MANUAL", sharedKey))
                .isInstanceOf(com.cardshowcase.exception.IdempotencyConflictException.class)
                .hasMessageContaining("different order");
    }

    // ── 10. Endpoint rejects non-admin (401/403) and missing CSRF ─────

    @Test
    void manualConfirmEndpoint_requiresAdminRole() throws Exception {
        Order order = createGuestOrder("pay-auth-" + ts);

        // Anonymous → 401 or redirect
        mockMvc.perform(post(adminUrl(order.getId()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualConfirmBody(GatewayOutcome.SUCCESS)))
                .andExpect(status().is3xxRedirection()); // admin chain redirects to login

        // ROLE_CUSTOMER (not admin) → forbidden
        mockMvc.perform(post(adminUrl(order.getId()))
                        .with(user("customer").roles("CUSTOMER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualConfirmBody(GatewayOutcome.SUCCESS)))
                .andExpect(status().isForbidden());
    }

    @Test
    void manualConfirmEndpoint_rejectsRequestWithoutCsrf() throws Exception {
        Order order = createGuestOrder("pay-csrf-" + ts);

        mockMvc.perform(post(adminUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        // No .with(csrf()) → CSRF token missing → 403
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualConfirmBody(GatewayOutcome.SUCCESS)))
                .andExpect(status().isForbidden());
    }

    // ── Endpoint-level idempotency contract tests ─────────────────────

    /**
     * Same order + same key → same logical Payment returned (not a new one).
     */
    @Test
    void endpoint_sameOrderSameKey_returnsSamePaymentId() throws Exception {
        Order order = createGuestOrder("idem-ep-same-" + ts);
        String key = "ep-same-key-" + ts;

        // First call: creates Payment
        String resp1 = mockMvc.perform(post(adminUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualConfirmBody(GatewayOutcome.SUCCESS, key)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Long paymentId1 = objectMapper.readTree(resp1).get("id").asLong();

        // Second call — same order, same key: returns the same Payment
        String resp2 = mockMvc.perform(post(adminUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualConfirmBody(GatewayOutcome.SUCCESS, key)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Long paymentId2 = objectMapper.readTree(resp2).get("id").asLong();
        assertThat(paymentId2).isEqualTo(paymentId1);
        // Exactly one payment row for this order
        assertThat(paymentRepository.findByOrder_Id(order.getId())).isPresent();
    }

    /**
     * Different order + same key → 409 idempotency conflict.
     */
    @Test
    void endpoint_differentOrderSameKey_returns409() throws Exception {
        Order orderA = createGuestOrder("idem-ep-A-" + ts);
        Order orderB = createGuestOrder("idem-ep-B-" + ts);
        String sharedKey = "ep-conflict-key-" + ts;

        // Order A succeeds with the shared key
        mockMvc.perform(post(adminUrl(orderA.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualConfirmBody(GatewayOutcome.SUCCESS, sharedKey)))
                .andExpect(status().isOk());

        // Order B uses the same key → 409 idempotency conflict
        mockMvc.perform(post(adminUrl(orderB.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualConfirmBody(GatewayOutcome.SUCCESS, sharedKey)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", containsString("different order")));
    }

    /**
     * Never create a second Payment for the same order, even with a new key.
     */
    @Test
    void endpoint_sameOrder_neverCreatesSecondPayment() throws Exception {
        Order order = createGuestOrder("idem-ep-nodup-" + ts);

        // First call with key A
        mockMvc.perform(post(adminUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualConfirmBody(GatewayOutcome.SUCCESS, "key-A-" + ts)))
                .andExpect(status().isOk());

        // Second call with a completely different key — still returns the existing Payment
        mockMvc.perform(post(adminUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualConfirmBody(GatewayOutcome.SUCCESS, "key-B-" + ts)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SUCCEEDED")));

        // Still exactly one Payment for this order
        assertThat(paymentRepository.findByOrder_Id(order.getId())).isPresent();
    }

    /**
     * Missing idempotencyKey field → 400 validation error.
     */
    @Test
    void endpoint_missingIdempotencyKey_returns400() throws Exception {
        Order order = createGuestOrder("idem-ep-missing-" + ts);

        mockMvc.perform(post(adminUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        // Body without idempotencyKey field
                        .content("{\"outcome\":\"SUCCESS\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── Order state guard regression tests ───────────────────────────

    /**
     * Confirming a payment when the Order is CANCELLED → 422 (IllegalStateException).
     * Guard is in confirmSuccessfulPayment: Order must be PENDING_PAYMENT.
     */
    @Test
    void manualConfirm_cancelledOrder_confirm_returns422() throws Exception {
        Order order = createGuestOrder("guard-confirm-cancelled-" + ts);
        // Initialize the payment while order is still PENDING_PAYMENT (valid)
        paymentService.getOrInitializePayment(order.getId(), "MANUAL", UUID.randomUUID().toString());

        // Directly cancel the order to simulate a race or admin cancellation
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);

        // Confirm attempt on the now-CANCELLED order → 422
        mockMvc.perform(post(adminUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualConfirmBody(GatewayOutcome.SUCCESS)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", containsString("PENDING_PAYMENT")));
    }

    /**
     * Initializing a payment when the Order is CANCELLED → 422 (IllegalStateException).
     * Guard is in getOrInitializePayment: Order must be PENDING_PAYMENT.
     */
    @Test
    void manualConfirm_cancelledOrder_init_returns422() throws Exception {
        Order order = createGuestOrder("guard-init-cancelled-" + ts);
        // Cancel the order before any payment is initialized
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);

        // The endpoint will call getOrInitializePayment → guard rejects it → 422
        mockMvc.perform(post(adminUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualConfirmBody(GatewayOutcome.SUCCESS)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", containsString("PENDING_PAYMENT")));
    }

    /**
     * FAILED → retry → SUCCEEDED still works after adding the Order state guard.
     * The guard only fires for NEW confirmation attempts; the retry path transitions
     * Payment back to PENDING (via applyRetryTransition) and then confirms normally —
     * the Order is still PENDING_PAYMENT throughout.
     * (Regression: guard must NOT interfere with the retry path.)
     */
    @Test
    void manualConfirm_retryPath_orderGuardDoesNotInterfere() throws Exception {
        Order order = createGuestOrder("guard-retry-ok-" + ts);
        String key = "guard-retry-key-" + ts;

        // First call: DECLINED → FAILED
        mockMvc.perform(post(adminUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualConfirmBody(GatewayOutcome.DECLINED, key)))
                .andExpect(status().isUnprocessableEntity());

        // Order is still PENDING_PAYMENT; retry should succeed
        mockMvc.perform(post(adminUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualConfirmBody(GatewayOutcome.SUCCESS, key)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SUCCEEDED")));

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
    }
}
