package com.cardshowcase;

import com.cardshowcase.model.dto.CheckoutRequest;
import com.cardshowcase.model.entity.*;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InventoryAllocationIntegrationTest extends BaseIntegrationTest {

    @Autowired ObjectMapper objectMapper;
    @Autowired CartService cartService;
    @Autowired CartRepository cartRepository;
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
    @Autowired InventoryAllocationRepository inventoryAllocationRepository;
    @Autowired OrderService orderService;
    @Autowired RefundService refundService;
    @Autowired AdminUserRepository adminUserRepository;
    @Autowired BCryptPasswordEncoder passwordEncoder;

    private ProductVariant variant;
    private InventoryLocation locationA;
    private InventoryLocation locationB;
    private AdminUser seniorAdmin;
    private long ts;

    @BeforeEach
    void setUp() {
        ts = System.nanoTime();

        Category cat = categoryRepository.save(Category.builder()
                .name("AllocCat-" + ts).slug("alloc-cat-" + ts).level(1).sortOrder(0).isActive(true).build());
        // Price $600 so subtotal >= $500 → NOT_REQUIRED (no supplemental shipping gate)
        Product product = productRepository.save(Product.builder()
                .name("Alloc Card " + ts).slug("alloc-" + ts).category(cat)
                .sortOrder(0).isActive(true).isOnSale(false).isNew(false)
                .isTrending(false).isBestSeller(false).isPreOrder(false).isFeatured(false).build());
        variant = variantRepository.save(ProductVariant.builder()
                .product(product).variantType("Box")
                .price(new BigDecimal("600.00")).isActive(true).build());

        locationA = locationRepository.save(InventoryLocation.builder()
                .name("AllocLocA-" + ts).isActive(true).build());
        locationB = locationRepository.save(InventoryLocation.builder()
                .name("AllocLocB-" + ts).isActive(true).build());

        seniorAdmin = adminUserRepository.save(AdminUser.builder()
                .username("alloc-senior-" + ts)
                .password(passwordEncoder.encode("pw"))
                .role("SENIOR_ADMIN")
                .build());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Order checkoutAndPay(int qty) throws Exception {
        // Set stock: locationA has 2, locationB has 10 (for multi-location tests)
        // Caller should set stock before calling this
        String token = "alloc-tok-" + ts + "-" + UUID.randomUUID();
        var cart = cartRepository.save(Cart.builder().sessionToken(token).build());
        cartService.addToCart(cart, variant.getId(), qty);

        CheckoutRequest req = new CheckoutRequest();
        req.setIdempotencyKey(UUID.randomUUID().toString());
        req.setGuestName("Alloc Guest");
        req.setGuestEmail("alloc-" + ts + "@example.com");
        req.setShippingFirstName("Alloc");
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

        mockMvc.perform(post("/admin/api/orders/" + orderId + "/payments/manual-confirm")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("outcome", "SUCCESS", "idempotencyKey", UUID.randomUUID().toString()))))
                .andExpect(status().isOk());

        return orderRepository.findById(orderId).orElseThrow();
    }

    private void submitAndApproveRefund(Order order) throws Exception {
        var submitResult = mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/refund-requests")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("amount", order.getTotal(), "reason", "Test refund"))))
                .andExpect(status().isOk())
                .andReturn();
        long rrId = objectMapper.readTree(submitResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/admin/api/refund-requests/" + rrId + "/approve")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    // ── A: Single-location allocation and refund restoration ────────────────

    @Test
    void A_singleLocation_allocationAndRefundRestoration() throws Exception {
        inventoryService.setStock(variant.getId(), locationA.getId(), 5);

        int stockBefore = inventoryService.getTotalStock(variant.getId());
        Order order = checkoutAndPay(1);

        // Allocation record created
        List<InventoryAllocation> allocs = inventoryAllocationRepository.findByOrderId(order.getId());
        assertThat(allocs).hasSize(1);
        assertThat(allocs.get(0).getQuantityCommitted()).isEqualTo(1);

        // Stock deducted
        int stockAfterPayment = inventoryService.getTotalStock(variant.getId());
        assertThat(stockAfterPayment).isEqualTo(stockBefore - 1);

        submitAndApproveRefund(order);

        // Stock restored to exact pre-payment value
        int stockAfterRefund = inventoryService.getTotalStock(variant.getId());
        assertThat(stockAfterRefund).isEqualTo(stockBefore);
    }

    // ── B: Multi-location: A=2, B=10, purchase=5 ────────────────────────────

    @Test
    void B_multiLocation_allocationRecordsAndRefundRestoration() throws Exception {
        inventoryService.setStock(variant.getId(), locationA.getId(), 2);
        inventoryService.setStock(variant.getId(), locationB.getId(), 10);

        // locationA.id < locationB.id (inserted in order), so deduction drains A first
        // Purchase qty=5: A=2 → 0, B=10 → 7
        Order order = checkoutAndPay(5);

        Inventory invA = inventoryRepository.findByVariantIdAndLocationId(variant.getId(), locationA.getId()).orElseThrow();
        Inventory invB = inventoryRepository.findByVariantIdAndLocationId(variant.getId(), locationB.getId()).orElseThrow();
        assertThat(invA.getQuantity()).isEqualTo(0);
        assertThat(invB.getQuantity()).isEqualTo(7);

        // Two allocation records: total committed = 5 (2 from A + 3 from B)
        List<InventoryAllocation> allocs = inventoryAllocationRepository.findByOrderId(order.getId());
        assertThat(allocs).hasSize(2);

        int totalCommitted = allocs.stream().mapToInt(InventoryAllocation::getQuantityCommitted).sum();
        assertThat(totalCommitted).isEqualTo(5);

        // Inventory rows reflect correct post-deduction quantities
        // (A=0, B=7 already asserted above)

        submitAndApproveRefund(order);

        // Inventory restored to original values
        invA = inventoryRepository.findByVariantIdAndLocationId(variant.getId(), locationA.getId()).orElseThrow();
        invB = inventoryRepository.findByVariantIdAndLocationId(variant.getId(), locationB.getId()).orElseThrow();
        assertThat(invA.getQuantity()).isEqualTo(2);
        assertThat(invB.getQuantity()).isEqualTo(10);
    }

    // ── C: Post-distribution change: refund uses persisted allocation ────────

    @Test
    void C_postDistributionChange_refundUsesPersistedAllocation() throws Exception {
        inventoryService.setStock(variant.getId(), locationA.getId(), 5);

        Order order = checkoutAndPay(1);

        // Manually change inventory quantity after payment
        Inventory inv = inventoryRepository.findByVariantIdAndLocationId(variant.getId(), locationA.getId()).orElseThrow();
        inv.setQuantity(99);
        inventoryRepository.save(inv);

        int stockBeforeRefund = inventoryService.getTotalStock(variant.getId());
        assertThat(stockBeforeRefund).isEqualTo(99);

        submitAndApproveRefund(order);

        // Refund restored the committed quantity (1), not based on current inventory
        Inventory invAfter = inventoryRepository.findByVariantIdAndLocationId(variant.getId(), locationA.getId()).orElseThrow();
        assertThat(invAfter.getQuantity()).isEqualTo(100); // 99 + 1 (allocation committed)
    }

    // ── D: SHIPPED refund: no inventory restoration ─────────────────────────

    @Test
    void D_shippedRefund_noInventoryRestoration() throws Exception {
        inventoryService.setStock(variant.getId(), locationA.getId(), 5);

        Order order = checkoutAndPay(1);

        // Record tracking, mark processing, dispatch
        mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/shipment/tracking")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("carrier", "UPS", "trackingNumber", "1Z-" + ts))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/mark-processing")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/dispatch")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk());

        int stockBeforeRefund = inventoryService.getTotalStock(variant.getId());

        submitAndApproveRefund(order);

        int stockAfterRefund = inventoryService.getTotalStock(variant.getId());
        assertThat(stockAfterRefund).isEqualTo(stockBeforeRefund);
    }

    // ── E: DELIVERED/COMPLETED refund: no inventory restoration ─────────────

    @Test
    void E_deliveredRefund_noInventoryRestoration() throws Exception {
        inventoryService.setStock(variant.getId(), locationA.getId(), 5);

        Order order = checkoutAndPay(1);

        // Track, process, dispatch, deliver
        mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/shipment/tracking")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("carrier", "FedEx", "trackingNumber", "FX-" + ts))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/mark-processing")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/dispatch")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/mark-delivered")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk());

        int stockBeforeRefund = inventoryService.getTotalStock(variant.getId());

        submitAndApproveRefund(order);

        int stockAfterRefund = inventoryService.getTotalStock(variant.getId());
        assertThat(stockAfterRefund).isEqualTo(stockBeforeRefund);
    }

    // ── F: Failed payment: no allocation records remain ──────────────────────

    @Test
    void F_failedPayment_noAllocationRecords() throws Exception {
        inventoryService.setStock(variant.getId(), locationA.getId(), 5);

        String token = "alloc-fail-" + ts;
        var cart = cartRepository.save(Cart.builder().sessionToken(token).build());
        cartService.addToCart(cart, variant.getId(), 1);

        CheckoutRequest req = new CheckoutRequest();
        req.setIdempotencyKey(UUID.randomUUID().toString());
        req.setGuestName("Fail Guest");
        req.setGuestEmail("fail-" + ts + "@example.com");
        req.setShippingFirstName("Fail");
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

        // Confirm with DECLINED outcome → payment FAILED (endpoint returns 422)
        mockMvc.perform(post("/admin/api/orders/" + orderId + "/payments/manual-confirm")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("outcome", "DECLINED", "idempotencyKey", UUID.randomUUID().toString()))))
                .andExpect(status().isUnprocessableEntity());

        // No allocation records should exist for a failed payment
        List<InventoryAllocation> allocs = inventoryAllocationRepository.findByOrderId(orderId);
        assertThat(allocs).isEmpty();
    }

    // ── G: Duplicate/concurrent refund approval: allocation restored exactly once ─

    @Test
    void G_concurrentRefundApproval_allocationRestoredExactlyOnce() throws Exception {
        inventoryService.setStock(variant.getId(), locationA.getId(), 5);

        Order order = checkoutAndPay(1);
        int stockBeforeRefund = inventoryService.getTotalStock(variant.getId());

        var submitResult = mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/refund-requests")
                        .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("amount", order.getTotal(), "reason", "Concurrent test"))))
                .andExpect(status().isOk())
                .andReturn();
        long rrId = objectMapper.readTree(submitResult.getResponse().getContentAsString()).get("id").asLong();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            futures.add(executor.submit(() -> {
                try {
                    var resp = mockMvc.perform(post("/admin/api/refund-requests/" + rrId + "/approve")
                                    .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                                    .with(csrf()))
                            .andReturn();
                    return resp.getResponse().getStatus();
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

        long successes = statuses.stream().filter(s -> s == 200).count();
        assertThat(successes).isEqualTo(1);

        // Allocation restored exactly once
        int stockAfterRefund = inventoryService.getTotalStock(variant.getId());
        assertThat(stockAfterRefund).isEqualTo(stockBeforeRefund + 1);
    }
}
