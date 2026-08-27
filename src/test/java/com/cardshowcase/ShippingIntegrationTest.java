package com.cardshowcase;

import com.cardshowcase.model.dto.CheckoutRequest;
import com.cardshowcase.model.entity.*;
import com.cardshowcase.payment.GatewayOutcome;
import com.cardshowcase.repository.*;
import com.cardshowcase.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ShippingIntegrationTest extends BaseIntegrationTest {

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
    @Autowired OrderRepository orderRepository;
    @Autowired ShipmentRepository shipmentRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentEventRepository paymentEventRepository;
    @Autowired OrderService orderService;
    @Autowired ShippingService shippingService;

    private ProductVariant variant;
    private long ts;

    @BeforeEach
    void setUp() {
        ts = System.nanoTime();

        Category cat = categoryRepository.save(Category.builder()
                .name("ShipCat-" + ts).slug("ship-cat-" + ts).level(1).sortOrder(0).isActive(true).build());
        Product product = productRepository.save(Product.builder()
                .name("Ship Card " + ts).slug("ship-prod-" + ts).category(cat)
                .sortOrder(0).isActive(true).isOnSale(false).isNew(false)
                .isTrending(false).isBestSeller(false).isPreOrder(false).isFeatured(false).build());
        variant = variantRepository.save(ProductVariant.builder()
                .product(product).variantType("Box")
                .price(new BigDecimal("50.00")).isActive(true).build());

        InventoryLocation loc = locationRepository.save(InventoryLocation.builder()
                .name("ShipLoc-" + ts).isActive(true).build());
        inventoryService.setStock(variant.getId(), loc.getId(), 20);
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private Order createOrderWithState(String token, String state, int qty) throws Exception {
        Cart cart = cartRepository.save(Cart.builder().sessionToken(token).build());
        cartService.addToCart(cart, variant.getId(), qty);

        CheckoutRequest req = new CheckoutRequest();
        req.setIdempotencyKey(UUID.randomUUID().toString());
        req.setGuestName("Test Guest");
        req.setGuestEmail("guest-" + ts + "@example.com");
        req.setShippingFirstName("Test");
        req.setShippingLastName("Guest");
        req.setShippingAddressLine1("100 Main St");
        req.setShippingCity("Anchorage");
        req.setShippingState(state);
        req.setShippingZip("99501");
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

    private void confirmPayment(long orderId) throws Exception {
        mockMvc.perform(post("/admin/api/orders/" + orderId + "/payments/manual-confirm")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("outcome", "SUCCESS",
                                       "idempotencyKey", UUID.randomUUID().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SUCCEEDED")));
    }

    private String quoteUrl(long orderId) {
        return "/admin/api/orders/" + orderId + "/shipment/quote";
    }

    private String requestPaymentUrl(long orderId) {
        return "/admin/api/orders/" + orderId + "/shipment/request-payment";
    }

    private String confirmPaymentUrl(long orderId) {
        return "/admin/api/orders/" + orderId + "/shipment/confirm-payment";
    }

    private String trackingUrl(long orderId) {
        return "/admin/api/orders/" + orderId + "/shipment/tracking";
    }

    // ── 1. Full AK/HI flow ────────────────────────────────────────────

    @Test
    void akHiFlow_fullLifecycle_checkoutToShippingPaid() throws Exception {
        Order order = createOrderWithState("ship-ak-" + ts, "AK", 3);

        // At checkout: NO Shipment yet (AK/HI deferred)
        assertThat(shipmentRepository.findByOrder_Id(order.getId())).isEmpty();
        // shipping_amount = $0 (no invented price at checkout for AK/HI)
        assertThat(order.getShippingAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        // Product payment → Order PAID → Shipment auto-created QUOTE_REQUIRED
        confirmPayment(order.getId());

        Shipment shipment = shipmentRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(shipment.getShippingPaymentStatus()).isEqualTo(ShippingPaymentStatus.QUOTE_REQUIRED);
        assertThat(shipment.getQuotedShippingAmount()).isNull();

        // isReadyForFulfillment: false while QUOTE_REQUIRED
        Order paidOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(orderService.isReadyForFulfillment(paidOrder)).isFalse();

        // Admin enters quote: QUOTE_REQUIRED → QUOTED (stops here)
        mockMvc.perform(post(quoteUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", "85.00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shippingPaymentStatus", is("QUOTED")))
                .andExpect(jsonPath("$.quotedShippingAmount", comparesEqualTo(85.00)));

        Shipment afterQuote = shipmentRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(afterQuote.getShippingPaymentStatus()).isEqualTo(ShippingPaymentStatus.QUOTED);
        assertThat(afterQuote.getQuotedShippingAmount()).isEqualByComparingTo("85.00");
        assertThat(afterQuote.getQuotedAt()).isNotNull();

        // isReadyForFulfillment: still false (QUOTED)
        assertThat(orderService.isReadyForFulfillment(paidOrder)).isFalse();

        // Admin requests payment: QUOTED → PAYMENT_PENDING
        mockMvc.perform(post(requestPaymentUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shippingPaymentStatus", is("PAYMENT_PENDING")));

        // isReadyForFulfillment: still false (PAYMENT_PENDING)
        assertThat(orderService.isReadyForFulfillment(paidOrder)).isFalse();

        // Admin confirms shipping payment received → PAID
        mockMvc.perform(post(confirmPaymentUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shippingPaymentStatus", is("PAID")));

        Shipment afterPay = shipmentRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(afterPay.getShippingPaymentStatus()).isEqualTo(ShippingPaymentStatus.PAID);
        assertThat(afterPay.getShippingPaidAt()).isNotNull();

        // isReadyForFulfillment: true now
        assertThat(orderService.isReadyForFulfillment(paidOrder)).isTrue();
    }

    // ── 2. Continental US (NOT_REQUIRED): ready immediately after PAID ─

    @Test
    void continentalUs_freeShipping_shipmentNotRequired_readyImmediately() throws Exception {
        // 10 items × $50 = $500 → free shipping, Shipment NOT_REQUIRED
        Order order = createOrderWithState("ship-ma-" + ts, "MA", 10);

        // Shipment created at checkout with NOT_REQUIRED
        Shipment shipment = shipmentRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(shipment.getShippingPaymentStatus()).isEqualTo(ShippingPaymentStatus.NOT_REQUIRED);
        assertThat(order.getShippingAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        // Not PAID yet → not ready
        assertThat(orderService.isReadyForFulfillment(order)).isFalse();

        // Pay
        confirmPayment(order.getId());
        Order paidOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(paidOrder.getStatus()).isEqualTo(OrderStatus.PAID);

        // Ready immediately — no supplemental shipping charge
        assertThat(orderService.isReadyForFulfillment(paidOrder)).isTrue();
    }

    // ── 3. Continental US (QUOTE_REQUIRED, Ground < $500) ────────────

    @Test
    void continentalUs_groundBelowThreshold_shipmentQuoteRequired_notReadyUntilResolved() throws Exception {
        // 3 items × $50 = $150 < $500 → REQUIRES_MANUAL_QUOTE, Shipment QUOTE_REQUIRED
        Order order = createOrderWithState("ship-ny-" + ts, "NY", 3);

        Shipment shipment = shipmentRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(shipment.getShippingPaymentStatus()).isEqualTo(ShippingPaymentStatus.QUOTE_REQUIRED);
        // shipping_amount = $0 (no invented price)
        assertThat(order.getShippingAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        // Pay product payment
        confirmPayment(order.getId());
        Order paidOrder = orderRepository.findById(order.getId()).orElseThrow();

        // PAID but outstanding shipping quote → not ready
        assertThat(orderService.isReadyForFulfillment(paidOrder)).isFalse();

        // Record quote (→ QUOTED), request payment (→ PAYMENT_PENDING), confirm → ready
        shippingService.recordQuote(order.getId(), new BigDecimal("15.00"));
        shippingService.requestShippingPayment(order.getId());
        shippingService.confirmShippingPaymentReceived(order.getId());
        assertThat(orderService.isReadyForFulfillment(paidOrder)).isTrue();
    }

    // ── 4. recordQuote requires admin auth ────────────────────────────

    @Test
    void recordQuote_requiresAdminAuth() throws Exception {
        Order order = createOrderWithState("ship-auth-q-" + ts, "AK", 3);
        confirmPayment(order.getId()); // creates Shipment QUOTE_REQUIRED

        // Anonymous → redirect to login
        mockMvc.perform(post(quoteUrl(order.getId()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", "50.00"))))
                .andExpect(status().is3xxRedirection());

        // Non-admin role → 403
        mockMvc.perform(post(quoteUrl(order.getId()))
                        .with(user("customer").roles("CUSTOMER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", "50.00"))))
                .andExpect(status().isForbidden());
    }

    // ── 5. confirmShippingPaymentReceived requires admin auth ─────────

    @Test
    void confirmShippingPayment_requiresAdminAuth() throws Exception {
        Order order = createOrderWithState("ship-auth-p-" + ts, "HI", 3);
        confirmPayment(order.getId());
        shippingService.recordQuote(order.getId(), new BigDecimal("120.00"));
        shippingService.requestShippingPayment(order.getId());

        // Anonymous → redirect
        mockMvc.perform(post(confirmPaymentUrl(order.getId())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // Non-admin → 403
        mockMvc.perform(post(confirmPaymentUrl(order.getId()))
                        .with(user("customer").roles("CUSTOMER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ── 6. recordTracking requires admin auth ─────────────────────────

    @Test
    void recordTracking_requiresAdminAuth() throws Exception {
        Order order = createOrderWithState("ship-auth-t-" + ts, "MA", 10);
        confirmPayment(order.getId()); // Shipment NOT_REQUIRED; tracking can be recorded regardless

        String trackingUrl = "/admin/api/orders/" + order.getId() + "/shipment/tracking";
        String body = objectMapper.writeValueAsString(
                Map.of("carrier", "UPS", "trackingNumber", "1Z999AA10123456784"));

        // Anonymous → redirect to login
        mockMvc.perform(post(trackingUrl)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is3xxRedirection());

        // Non-admin role → 403
        mockMvc.perform(post(trackingUrl)
                        .with(user("customer").roles("CUSTOMER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        // Admin → succeeds
        mockMvc.perform(post(trackingUrl)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingNumber", is("1Z999AA10123456784")))
                .andExpect(jsonPath("$.carrier", is("UPS")));
    }

    // ── 8. confirmShippingPaymentReceived does NOT touch Payment tables ─

    @Test
    void confirmShippingPayment_doesNotCreatePaymentOrEvent() throws Exception {
        Order order = createOrderWithState("ship-isolation-" + ts, "AK", 3);
        confirmPayment(order.getId());
        shippingService.recordQuote(order.getId(), new BigDecimal("99.00"));
        shippingService.requestShippingPayment(order.getId());

        long paymentCountBefore = paymentRepository.count();
        long eventCountBefore   = paymentEventRepository.count();

        // Confirm shipping payment via endpoint
        mockMvc.perform(post(confirmPaymentUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shippingPaymentStatus", is("PAID")));

        // Payment and PaymentEvent tables must be untouched
        assertThat(paymentRepository.count()).isEqualTo(paymentCountBefore);
        assertThat(paymentEventRepository.count()).isEqualTo(eventCountBefore);

        // Verify Order.status and Payment.status are both unchanged (still PAID / SUCCEEDED)
        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAID);

        com.cardshowcase.model.entity.Payment payment =
                paymentRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    // ── Issue 1 regressions: recordTracking fulfillment-readiness guard ──

    @Test
    void recordTracking_quoteRequired_rejected() throws Exception {
        // Ground < $500 → QUOTE_REQUIRED at checkout; pay product price → PAID order
        Order order = createOrderWithState("ship-trk-qr-" + ts, "NY", 3);
        confirmPayment(order.getId()); // order = PAID, shipment = QUOTE_REQUIRED

        // recordTracking must be rejected: shipping not yet resolved
        mockMvc.perform(post(trackingUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("carrier", "UPS", "trackingNumber", "1Z999"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void recordTracking_paymentPending_rejected() throws Exception {
        Order order = createOrderWithState("ship-trk-pp-" + ts, "AK", 3);
        confirmPayment(order.getId());
        shippingService.recordQuote(order.getId(), new BigDecimal("30.00"));
        shippingService.requestShippingPayment(order.getId()); // → PAYMENT_PENDING

        mockMvc.perform(post(trackingUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("carrier", "FedEx", "trackingNumber", "123456"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void recordTracking_pendingPaymentOrder_rejected() throws Exception {
        // Order still PENDING_PAYMENT (not yet paid at all)
        Order order = createOrderWithState("ship-trk-ord-" + ts, "MA", 10);
        // Shipment is NOT_REQUIRED but order is PENDING_PAYMENT → not fulfillment-ready

        mockMvc.perform(post(trackingUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("carrier", "UPS", "trackingNumber", "1Z999"))))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── Issue 2 regressions: QUOTED is now observable ─────────────────

    @Test
    void recordQuote_stopsAtQuoted_notPaymentPending() throws Exception {
        Order order = createOrderWithState("ship-quoted-" + ts, "HI", 3);
        confirmPayment(order.getId());

        mockMvc.perform(post(quoteUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", "45.00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shippingPaymentStatus", is("QUOTED")));

        Shipment s = shipmentRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(s.getShippingPaymentStatus()).isEqualTo(ShippingPaymentStatus.QUOTED);
    }

    @Test
    void requestShippingPayment_quotedToPaymentPending() throws Exception {
        Order order = createOrderWithState("ship-rsp-" + ts, "AK", 3);
        confirmPayment(order.getId());
        shippingService.recordQuote(order.getId(), new BigDecimal("55.00"));

        mockMvc.perform(post(requestPaymentUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shippingPaymentStatus", is("PAYMENT_PENDING")));
    }

    @Test
    void requestShippingPayment_requiresQuotedStatus() throws Exception {
        // Calling request-payment before recordQuote → still QUOTE_REQUIRED → 422
        Order order = createOrderWithState("ship-rsp-bad-" + ts, "AK", 3);
        confirmPayment(order.getId()); // shipment = QUOTE_REQUIRED

        mockMvc.perform(post(requestPaymentUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void requestShippingPayment_requiresAdminAuth() throws Exception {
        Order order = createOrderWithState("ship-rsp-auth-" + ts, "AK", 3);
        confirmPayment(order.getId());
        shippingService.recordQuote(order.getId(), new BigDecimal("60.00"));

        // Anonymous → redirect
        mockMvc.perform(post(requestPaymentUrl(order.getId())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // Non-admin → 403
        mockMvc.perform(post(requestPaymentUrl(order.getId()))
                        .with(user("customer").roles("CUSTOMER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ── Week 6 second review regressions ─────────────────────────────

    // Issue 3: recordTracking must NOT set shippedAt
    @Test
    void recordTracking_doesNotSetShippedAt() throws Exception {
        Order order = createOrderWithState("ship-trk-noship-" + ts, "MA", 10);
        confirmPayment(order.getId()); // Shipment NOT_REQUIRED, Order PAID

        mockMvc.perform(post(trackingUrl(order.getId()))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("carrier", "UPS", "trackingNumber", "1Z999AA10123456784"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carrier", is("UPS")))
                .andExpect(jsonPath("$.trackingNumber", is("1Z999AA10123456784")));

        Shipment shipment = shipmentRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(shipment.getCarrier()).isEqualTo("UPS");
        assertThat(shipment.getTrackingNumber()).isEqualTo("1Z999AA10123456784");
        assertThat(shipment.getShippedAt()).isNull(); // dispatch is a separate action
    }

    // Issue 1: GROUND vs NEXT_DAY_AIR with same idempotency key → 409
    @Test
    void fingerprint_serviceLevelDifference_causesConflict() throws Exception {
        String token = "ship-fp-sl-" + ts;
        String key   = UUID.randomUUID().toString();

        Cart cart = cartRepository.save(Cart.builder().sessionToken(token).build());
        cartService.addToCart(cart, variant.getId(), 3);

        // First checkout: GROUND
        CheckoutRequest req1 = buildFpRequest(key, token, "FP", "Test", ServiceLevel.GROUND);
        mockMvc.perform(post("/api/checkout")
                        .with(csrf())
                        .cookie(new MockCookie("cart_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isOk());

        // clearCart deleted items but kept the Cart entity.
        // Restock and re-add to the SAME cart — identity (guest cookie) stays identical.
        InventoryLocation loc2 = locationRepository.save(
                InventoryLocation.builder().name("ShipLoc2-" + ts).isActive(true).build());
        inventoryService.setStock(variant.getId(), loc2.getId(), 3);
        cartService.addToCart(cart, variant.getId(), 3);

        // Same key, same cookie (same guest identity), same cart contents, NEXT_DAY_AIR → 409
        CheckoutRequest req2 = buildFpRequest(key, token, "FP", "Test", ServiceLevel.NEXT_DAY_AIR);
        mockMvc.perform(post("/api/checkout")
                        .with(csrf())
                        .cookie(new MockCookie("cart_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", containsString("different request content")));
    }

    // Fingerprint: changing recipient name with the same key → 409
    @Test
    void fingerprint_addressFieldChange_causesConflict() throws Exception {
        String token = "ship-fp-addr-" + ts;
        String key   = UUID.randomUUID().toString();

        Cart cart = cartRepository.save(Cart.builder().sessionToken(token).build());
        cartService.addToCart(cart, variant.getId(), 3);

        // First checkout: firstName = "John"
        CheckoutRequest req1 = buildFpRequest(key, token, "John", "Smith", ServiceLevel.GROUND);
        mockMvc.perform(post("/api/checkout")
                        .with(csrf())
                        .cookie(new MockCookie("cart_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isOk());

        // Restock and re-add to the SAME cart
        InventoryLocation loc2 = locationRepository.save(
                InventoryLocation.builder().name("ShipLoc3-" + ts).isActive(true).build());
        inventoryService.setStock(variant.getId(), loc2.getId(), 3);
        cartService.addToCart(cart, variant.getId(), 3);

        // Same key, same identity, firstName changed to "Jane" → fingerprint differs → 409
        CheckoutRequest req2 = buildFpRequest(key, token, "Jane", "Smith", ServiceLevel.GROUND);
        mockMvc.perform(post("/api/checkout")
                        .with(csrf())
                        .cookie(new MockCookie("cart_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", containsString("different request content")));
    }

    /** Builds a minimal guest checkout request with the given idempotency key, serviceLevel,
     *  and shipping firstName/lastName. All other address fields are fixed. */
    private CheckoutRequest buildFpRequest(String key, String token,
                                           String firstName, String lastName,
                                           ServiceLevel serviceLevel) {
        CheckoutRequest req = new CheckoutRequest();
        req.setIdempotencyKey(key);
        req.setGuestName(firstName + " " + lastName);
        req.setGuestEmail("fp-" + token + "@example.com");
        req.setShippingFirstName(firstName);
        req.setShippingLastName(lastName);
        req.setShippingAddressLine1("1 Main St");
        req.setShippingCity("Boston");
        req.setShippingState("MA");
        req.setShippingZip("02101");
        req.setShippingCountry("US");
        req.setBillingSameAsShipping(true);
        req.setServiceLevel(serviceLevel);
        return req;
    }

    // Issue 2: non-US country is rejected at checkout
    @Test
    void checkout_nonUsCountry_returns400() throws Exception {
        String token = "ship-dest-ca-" + ts;
        Cart cart = cartRepository.save(Cart.builder().sessionToken(token).build());
        cartService.addToCart(cart, variant.getId(), 3);

        CheckoutRequest req = new CheckoutRequest();
        req.setIdempotencyKey(UUID.randomUUID().toString());
        req.setGuestName("CA Guest");
        req.setGuestEmail("ca-" + ts + "@example.com");
        req.setShippingFirstName("CA");
        req.setShippingLastName("Guest");
        req.setShippingAddressLine1("1 Maple St");
        req.setShippingCity("Toronto");
        req.setShippingState("ON");
        req.setShippingZip("M5V3A8");
        req.setShippingCountry("CA");
        req.setBillingSameAsShipping(true);

        mockMvc.perform(post("/api/checkout")
                        .with(csrf())
                        .cookie(new MockCookie("cart_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("US domestic")));
    }

    // Issue 2: invalid US state code is rejected at checkout
    @Test
    void checkout_invalidUsState_returns400() throws Exception {
        String token = "ship-dest-zz-" + ts;
        Cart cart = cartRepository.save(Cart.builder().sessionToken(token).build());
        cartService.addToCart(cart, variant.getId(), 3);

        CheckoutRequest req = new CheckoutRequest();
        req.setIdempotencyKey(UUID.randomUUID().toString());
        req.setGuestName("ZZ Guest");
        req.setGuestEmail("zz-" + ts + "@example.com");
        req.setShippingFirstName("ZZ");
        req.setShippingLastName("Guest");
        req.setShippingAddressLine1("1 Nowhere Ln");
        req.setShippingCity("Springfield");
        req.setShippingState("ZZ");
        req.setShippingZip("00000");
        req.setShippingCountry("US");
        req.setBillingSameAsShipping(true);

        mockMvc.perform(post("/api/checkout")
                        .with(csrf())
                        .cookie(new MockCookie("cart_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("ZZ")));
    }

    // ── 7. Next Day Air surcharge applied at checkout ─────────────────

    @Test
    void nextDayAir_shippingAmountIsOneFifty() throws Exception {
        Cart cart = cartRepository.save(Cart.builder().sessionToken("ship-nda-" + ts).build());
        cartService.addToCart(cart, variant.getId(), 3); // $150 subtotal

        CheckoutRequest req = new CheckoutRequest();
        req.setIdempotencyKey(UUID.randomUUID().toString());
        req.setGuestName("NDA Guest");
        req.setGuestEmail("nda-" + ts + "@example.com");
        req.setShippingFirstName("NDA");
        req.setShippingLastName("Guest");
        req.setShippingAddressLine1("1 Fast Rd");
        req.setShippingCity("Chicago");
        req.setShippingState("IL");
        req.setShippingZip("60601");
        req.setShippingCountry("US");
        req.setBillingSameAsShipping(true);
        req.setServiceLevel(com.cardshowcase.model.entity.ServiceLevel.NEXT_DAY_AIR);

        var result = mockMvc.perform(post("/api/checkout")
                        .with(csrf())
                        .cookie(new MockCookie("cart_token", "ship-nda-" + ts))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();

        long orderId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asLong();
        Order order = orderRepository.findById(orderId).orElseThrow();

        // $150 surcharge applied
        assertThat(order.getShippingAmount()).isEqualByComparingTo("150.00");
        // Total = subtotal $150 + shipping $150 = $300
        assertThat(order.getTotal()).isEqualByComparingTo("300.00");

        // Shipment created with NOT_REQUIRED (resolved at checkout)
        Shipment shipment = shipmentRepository.findByOrder_Id(orderId).orElseThrow();
        assertThat(shipment.getShippingPaymentStatus()).isEqualTo(ShippingPaymentStatus.NOT_REQUIRED);
        assertThat(shipment.getServiceLevel()).isEqualTo(com.cardshowcase.model.entity.ServiceLevel.NEXT_DAY_AIR);
    }
}
