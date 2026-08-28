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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RefundWorkflowIntegrationTest extends BaseIntegrationTest {

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
    @Autowired OrderItemRepository orderItemRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentEventRepository paymentEventRepository;
    @Autowired ShipmentRepository shipmentRepository;
    @Autowired RefundRequestRepository refundRequestRepository;
    @Autowired OrderService orderService;
    @Autowired ShippingService shippingService;
    @Autowired RefundService refundService;
    @Autowired AdminUserRepository adminUserRepository;
    @Autowired BCryptPasswordEncoder passwordEncoder;

    private ProductVariant variant;
    private InventoryLocation locationA;
    private AdminUser seniorAdmin;
    private AdminUser regularAdmin;
    private long ts;

    @BeforeEach
    void setUp() {
        ts = System.nanoTime();

        Category cat = categoryRepository.save(Category.builder()
                .name("Cat-" + ts).slug("cat-" + ts).level(1).sortOrder(0).isActive(true).build());
        // Use price >= $500 so we get free shipping (NOT_REQUIRED), not QUOTE_REQUIRED
        Product product = productRepository.save(Product.builder()
                .name("Test Card " + ts).slug("prod-" + ts).category(cat)
                .sortOrder(0).isActive(true).isOnSale(false).isNew(false)
                .isTrending(false).isBestSeller(false).isPreOrder(false).isFeatured(false).build());
        variant = variantRepository.save(ProductVariant.builder()
                .product(product).variantType("Box")
                .price(new BigDecimal("600.00")).isActive(true).build());

        locationA = locationRepository.save(InventoryLocation.builder()
                .name("LocA-" + ts).isActive(true).build());
        inventoryService.setStock(variant.getId(), locationA.getId(), 10);

        seniorAdmin = adminUserRepository.save(AdminUser.builder()
                .username("senior-" + ts)
                .password(passwordEncoder.encode("pw"))
                .role("SENIOR_ADMIN")
                .build());

        regularAdmin = adminUserRepository.save(AdminUser.builder()
                .username("admin-" + ts)
                .password(passwordEncoder.encode("pw"))
                .role("ADMIN")
                .build());
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private Order createPaidOrder() throws Exception {
        String token = "rw-token-" + ts + "-" + UUID.randomUUID();
        Cart cart = cartRepository.save(Cart.builder().sessionToken(token).build());
        cartService.addToCart(cart, variant.getId(), 1);

        CheckoutRequest req = new CheckoutRequest();
        req.setIdempotencyKey(UUID.randomUUID().toString());
        req.setGuestName("Refund Test Guest");
        req.setGuestEmail("refund-" + ts + "@example.com");
        req.setShippingFirstName("Refund");
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

        long orderId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        Order order = orderRepository.findById(orderId).orElseThrow();

        // Confirm payment to move to PAID
        String confirmBody = objectMapper.writeValueAsString(
                Map.of("outcome", "SUCCESS", "idempotencyKey", UUID.randomUUID().toString()));
        mockMvc.perform(post("/admin/api/orders/" + orderId + "/payments/manual-confirm")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody))
                .andExpect(status().isOk());

        return orderRepository.findById(orderId).orElseThrow();
    }

    private String refundRequestBody(BigDecimal amount) throws Exception {
        return objectMapper.writeValueAsString(Map.of("amount", amount, "reason", "Test refund"));
    }

    // ── Test 1: Refund PAID order → restores inventory ───────────────────

    @Test
    void refund_paid_order_restoresInventory() throws Exception {
        Order order = createPaidOrder();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);

        int stockBefore = inventoryService.getTotalStock(variant.getId());

        // Submit refund request
        var submitResult = mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/refund-requests")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundRequestBody(order.getTotal())))
                .andExpect(status().isOk())
                .andReturn();

        long rrId = objectMapper.readTree(submitResult.getResponse().getContentAsString()).get("id").asLong();

        // Approve refund request
        mockMvc.perform(post("/admin/api/refund-requests/" + rrId + "/approve")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk());

        // Assertions
        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.REFUNDED);

        Payment payment = paymentRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);

        int stockAfter = inventoryService.getTotalStock(variant.getId());
        assertThat(stockAfter).isEqualTo(stockBefore + 1); // 1 item was in the order

        RefundRequest rr = refundRequestRepository.findById(rrId).orElseThrow();
        assertThat(rr.getStatus()).isEqualTo(RefundRequestStatus.EXECUTED);

        long refundEvents = paymentEventRepository.findByPayment_IdOrderByCreatedAtAsc(payment.getId())
                .stream().filter(e -> e.getEventType() == PaymentEventType.PAYMENT_REFUNDED).count();
        assertThat(refundEvents).isEqualTo(1);
    }

    // ── Test 2: Refund PROCESSING order → restores inventory ─────────────

    @Test
    void refund_processing_order_restoresInventory() throws Exception {
        Order order = createPaidOrder();

        // Mark processing
        mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/mark-processing")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk());

        int stockBefore = inventoryService.getTotalStock(variant.getId());

        var submitResult = mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/refund-requests")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundRequestBody(order.getTotal())))
                .andExpect(status().isOk())
                .andReturn();

        long rrId = objectMapper.readTree(submitResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/admin/api/refund-requests/" + rrId + "/approve")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk());

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.REFUNDED);

        Payment payment = paymentRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);

        int stockAfter = inventoryService.getTotalStock(variant.getId());
        assertThat(stockAfter).isEqualTo(stockBefore + 1);
    }

    // ── Test 3: Refund SHIPPED order → does NOT restore inventory ────────

    @Test
    void refund_shipped_order_doesNotRestoreInventory() throws Exception {
        Order order = createPaidOrder();

        // Record tracking while PAID (recordTracking requires PAID status)
        mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/shipment/tracking")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("carrier", "UPS", "trackingNumber", "1Z999-" + ts))))
                .andExpect(status().isOk());

        // Mark PROCESSING
        mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/mark-processing")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk());

        // Dispatch → SHIPPED
        mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/dispatch")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk());

        int stockBeforeRefund = inventoryService.getTotalStock(variant.getId());

        var submitResult = mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/refund-requests")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundRequestBody(order.getTotal())))
                .andExpect(status().isOk())
                .andReturn();

        long rrId = objectMapper.readTree(submitResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/admin/api/refund-requests/" + rrId + "/approve")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk());

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.REFUNDED);

        Payment payment = paymentRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);

        // Inventory NOT restored (order was shipped)
        int stockAfter = inventoryService.getTotalStock(variant.getId());
        assertThat(stockAfter).isEqualTo(stockBeforeRefund);
    }

    // ── Test 4: Reject refund request ────────────────────────────────────

    @Test
    void reject_refundRequest_leavesEverythingUntouched() throws Exception {
        Order order = createPaidOrder();

        Payment paymentBefore = paymentRepository.findByOrder_Id(order.getId()).orElseThrow();
        PaymentStatus paymentStatusBefore = paymentBefore.getStatus();
        OrderStatus orderStatusBefore = order.getStatus();
        int stockBefore = inventoryService.getTotalStock(variant.getId());

        var submitResult = mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/refund-requests")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundRequestBody(order.getTotal())))
                .andExpect(status().isOk())
                .andReturn();

        long rrId = objectMapper.readTree(submitResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/admin/api/refund-requests/" + rrId + "/reject")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("rejectionReason", "Not eligible"))))
                .andExpect(status().isOk());

        // Payment unchanged
        Payment paymentAfter = paymentRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(paymentAfter.getStatus()).isEqualTo(paymentStatusBefore);

        // Order unchanged
        Order orderAfter = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(orderAfter.getStatus()).isEqualTo(orderStatusBefore);

        // Inventory unchanged
        assertThat(inventoryService.getTotalStock(variant.getId())).isEqualTo(stockBefore);

        // RefundRequest is REJECTED
        RefundRequest rr = refundRequestRepository.findById(rrId).orElseThrow();
        assertThat(rr.getStatus()).isEqualTo(RefundRequestStatus.REJECTED);
    }

    // ── Test 5: Regular ADMIN can submit, cannot approve or reject ────────

    @Test
    void regularAdmin_canSubmit_cannotApproveOrReject() throws Exception {
        Order order = createPaidOrder();

        // Regular admin can submit
        var submitResult = mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/refund-requests")
                        .with(user(regularAdmin.getUsername()).roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundRequestBody(order.getTotal())))
                .andExpect(status().isOk())
                .andReturn();

        long rrId = objectMapper.readTree(submitResult.getResponse().getContentAsString()).get("id").asLong();

        // Regular admin cannot approve (403)
        mockMvc.perform(post("/admin/api/refund-requests/" + rrId + "/approve")
                        .with(user(regularAdmin.getUsername()).roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        // Regular admin cannot reject (403)
        mockMvc.perform(post("/admin/api/refund-requests/" + rrId + "/reject")
                        .with(user(regularAdmin.getUsername()).roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("rejectionReason", "test"))))
                .andExpect(status().isForbidden());
    }

    // ── Test 6: Senior admin can approve and self-approve ─────────────────

    @Test
    void seniorAdmin_canApproveAndSelfApprove() throws Exception {
        Order order = createPaidOrder();

        // Senior admin submits
        var submitResult = mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/refund-requests")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundRequestBody(order.getTotal())))
                .andExpect(status().isOk())
                .andReturn();

        long rrId = objectMapper.readTree(submitResult.getResponse().getContentAsString()).get("id").asLong();

        // Same senior admin approves (self-approve)
        mockMvc.perform(post("/admin/api/refund-requests/" + rrId + "/approve")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk());

        RefundRequest rr = refundRequestRepository.findById(rrId).orElseThrow();
        assertThat(rr.getStatus()).isEqualTo(RefundRequestStatus.EXECUTED);
    }

    // ── Test 7: Cannot submit refund for PENDING_PAYMENT order ───────────

    @Test
    void cannot_submitRefund_for_pendingPayment_order() throws Exception {
        // Create order (not paid)
        String token = "rw-pending-" + ts;
        Cart cart = cartRepository.save(Cart.builder().sessionToken(token).build());
        cartService.addToCart(cart, variant.getId(), 1);

        CheckoutRequest req = new CheckoutRequest();
        req.setIdempotencyKey(UUID.randomUUID().toString());
        req.setGuestName("Pending Guest");
        req.setGuestEmail("pending-" + ts + "@example.com");
        req.setShippingFirstName("Pending");
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

        long orderId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();

        // Try to submit refund — should fail (order is PENDING_PAYMENT)
        mockMvc.perform(post("/admin/api/orders/" + orderId + "/refund-requests")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundRequestBody(new BigDecimal("600.00"))))
                .andExpect(status().is4xxClientError());
    }

    // ── Test 8: Cannot submit refund for CANCELLED order ─────────────────

    @Test
    void cannot_submitRefund_for_cancelled_order() throws Exception {
        // Create a PENDING_PAYMENT order and cancel it directly
        String token = "rw-cancel-" + ts;
        Cart cart = cartRepository.save(Cart.builder().sessionToken(token).build());
        cartService.addToCart(cart, variant.getId(), 1);

        CheckoutRequest req = new CheckoutRequest();
        req.setIdempotencyKey(UUID.randomUUID().toString());
        req.setGuestName("Cancel Guest");
        req.setGuestEmail("cancel-" + ts + "@example.com");
        req.setShippingFirstName("Cancel");
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

        long orderId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();

        // Cancel it
        Order order = orderRepository.findById(orderId).orElseThrow();
        orderService.transitionTo(order, OrderStatus.CANCELLED);

        // Try to submit refund — should fail
        mockMvc.perform(post("/admin/api/orders/" + orderId + "/refund-requests")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundRequestBody(new BigDecimal("600.00"))))
                .andExpect(status().is4xxClientError());
    }

    // ── Test 9: Cannot submit second refund when first is executed ────────

    @Test
    void cannot_submit_second_refund_when_first_is_executed() throws Exception {
        Order order = createPaidOrder();

        // Submit and approve first refund
        var submitResult = mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/refund-requests")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundRequestBody(order.getTotal())))
                .andExpect(status().isOk())
                .andReturn();

        long rrId = objectMapper.readTree(submitResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/admin/api/refund-requests/" + rrId + "/approve")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk());

        // Try to submit another refund — should fail with 422
        mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/refund-requests")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundRequestBody(order.getTotal())))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── Test 10: Partial amount is rejected at submission time ───────────

    @Test
    void submit_partialAmount_returns400() throws Exception {
        Order order = createPaidOrder();

        // Subtract $1 to get an amount less than the full total
        BigDecimal partialAmount = order.getTotal().subtract(BigDecimal.ONE);

        mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/refund-requests")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("amount", partialAmount, "reason", "partial test"))))
                .andExpect(status().isBadRequest());

        // Nothing was persisted
        assertThat(refundRequestRepository.findByOrder_IdOrderByCreatedAtAsc(order.getId())).isEmpty();
    }

    // ── Test 11: Concurrent approval — exactly one succeeds ──────────────

    @Test
    void concurrent_approval_exactlyOnce() throws Exception {
        Order order = createPaidOrder();
        int stockBefore = inventoryService.getTotalStock(variant.getId());

        var submitResult = mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/refund-requests")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundRequestBody(order.getTotal())))
                .andExpect(status().isOk())
                .andReturn();

        long rrId = objectMapper.readTree(submitResult.getResponse().getContentAsString()).get("id").asLong();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            futures.add(executor.submit(() -> {
                try {
                    var response = mockMvc.perform(post("/admin/api/refund-requests/" + rrId + "/approve")
                                    .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                                    .with(csrf()))
                            .andReturn();
                    return response.getResponse().getStatus();
                } catch (Exception e) {
                    return 500;
                }
            }));
        }

        executor.shutdown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : futures) {
            statuses.add(f.get());
        }

        // Exactly one 200 and one non-200 (409 or 422)
        long successCount = statuses.stream().filter(s -> s == 200).count();
        long failCount = statuses.stream().filter(s -> s != 200).count();
        assertThat(successCount).isEqualTo(1);
        assertThat(failCount).isEqualTo(1);

        // Inventory restored exactly once
        int stockAfter = inventoryService.getTotalStock(variant.getId());
        assertThat(stockAfter).isEqualTo(stockBefore + 1);

        // Exactly one PAYMENT_REFUNDED event
        Payment payment = paymentRepository.findByOrder_Id(order.getId()).orElseThrow();
        long refundEvents = paymentEventRepository.findByPayment_IdOrderByCreatedAtAsc(payment.getId())
                .stream().filter(e -> e.getEventType() == PaymentEventType.PAYMENT_REFUNDED).count();
        assertThat(refundEvents).isEqualTo(1);
    }
}
