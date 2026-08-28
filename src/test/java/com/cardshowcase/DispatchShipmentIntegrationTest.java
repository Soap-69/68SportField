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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DispatchShipmentIntegrationTest extends BaseIntegrationTest {

    @Autowired ObjectMapper objectMapper;
    @Autowired CartService cartService;
    @Autowired CartRepository cartRepository;
    @Autowired CartItemRepository cartItemRepository;
    @Autowired ProductRepository productRepository;
    @Autowired ProductVariantRepository variantRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired InventoryLocationRepository locationRepository;
    @Autowired InventoryService inventoryService;
    @Autowired OrderRepository orderRepository;
    @Autowired ShipmentRepository shipmentRepository;
    @Autowired AdminUserRepository adminUserRepository;
    @Autowired BCryptPasswordEncoder passwordEncoder;

    private ProductVariant variant;
    private AdminUser admin;
    private long ts;

    @BeforeEach
    void setUp() {
        ts = System.nanoTime();

        Category cat = categoryRepository.save(Category.builder()
                .name("DCat-" + ts).slug("dcat-" + ts).level(1).sortOrder(0).isActive(true).build());
        Product product = productRepository.save(Product.builder()
                .name("Dispatch Card " + ts).slug("dprod-" + ts).category(cat)
                .sortOrder(0).isActive(true).isOnSale(false).isNew(false)
                .isTrending(false).isBestSeller(false).isPreOrder(false).isFeatured(false).build());
        variant = variantRepository.save(ProductVariant.builder()
                .product(product).variantType("Box")
                .price(new BigDecimal("600.00")).isActive(true).build());

        var location = locationRepository.save(InventoryLocation.builder()
                .name("DLoc-" + ts).isActive(true).build());
        inventoryService.setStock(variant.getId(), location.getId(), 10);

        admin = adminUserRepository.save(AdminUser.builder()
                .username("dispatch-admin-" + ts)
                .password(passwordEncoder.encode("pw"))
                .role("SENIOR_ADMIN")
                .build());
    }

    private Order createPaidOrder() throws Exception {
        String token = "dispatch-token-" + ts + "-" + UUID.randomUUID();
        var cart = cartRepository.save(Cart.builder().sessionToken(token).build());
        cartService.addToCart(cart, variant.getId(), 1);

        CheckoutRequest req = new CheckoutRequest();
        req.setIdempotencyKey(UUID.randomUUID().toString());
        req.setGuestName("Dispatch Test");
        req.setGuestEmail("dispatch-" + ts + "@example.com");
        req.setShippingFirstName("Dispatch");
        req.setShippingLastName("Test");
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

        // Confirm payment → PAID
        mockMvc.perform(post("/admin/api/orders/" + orderId + "/payments/manual-confirm")
                        .with(user(admin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("outcome", "SUCCESS", "idempotencyKey", UUID.randomUUID().toString()))))
                .andExpect(status().isOk());

        return orderRepository.findById(orderId).orElseThrow();
    }

    // ── Test 1: Dispatch requires PROCESSING status ───────────────────────

    @Test
    void dispatch_requiresProcessingStatus() throws Exception {
        Order order = createPaidOrder(); // PAID, not PROCESSING
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);

        mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/dispatch")
                        .with(user(admin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── Test 2: Dispatch requires tracking ───────────────────────────────

    @Test
    void dispatch_requiresTrackingRecorded() throws Exception {
        Order order = createPaidOrder();

        // Mark PROCESSING
        mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/mark-processing")
                        .with(user(admin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk());

        // Try to dispatch without tracking — should fail with 422
        mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/dispatch")
                        .with(user(admin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── Test 3: Dispatch atomically sets SHIPPED and shippedAt ───────────

    @Test
    void dispatch_atomicallySetsShippedAndShippedAt() throws Exception {
        Order order = createPaidOrder();

        // Record tracking while PAID (recordTracking requires PAID status)
        mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/shipment/tracking")
                        .with(user(admin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("carrier", "FedEx", "trackingNumber", "FX123-" + ts))))
                .andExpect(status().isOk());

        // Mark PROCESSING
        mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/mark-processing")
                        .with(user(admin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk());

        // Dispatch
        mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/dispatch")
                        .with(user(admin.getUsername()).roles("SENIOR_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk());

        // Verify both order status and shippedAt
        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.SHIPPED);

        Shipment shipment = shipmentRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(shipment.getShippedAt()).isNotNull();
    }

    // ── Test 4: Dispatch requires admin auth ─────────────────────────────

    @Test
    void dispatch_requiresAdminAuth() throws Exception {
        Order order = createPaidOrder();

        // Anonymous → redirect to login
        mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/dispatch")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        // CUSTOMER role → forbidden
        mockMvc.perform(post("/admin/api/orders/" + order.getId() + "/dispatch")
                        .with(user("customer").roles("CUSTOMER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }
}
