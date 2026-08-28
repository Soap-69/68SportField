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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminOrderExportTest extends BaseIntegrationTest {

    @Autowired ObjectMapper objectMapper;
    @Autowired CartService cartService;
    @Autowired CartRepository cartRepository;
    @Autowired ProductRepository productRepository;
    @Autowired ProductVariantRepository variantRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired InventoryLocationRepository locationRepository;
    @Autowired InventoryService inventoryService;
    @Autowired OrderRepository orderRepository;
    @Autowired AdminUserRepository adminUserRepository;
    @Autowired BCryptPasswordEncoder passwordEncoder;

    private ProductVariant variant;
    private AdminUser admin;
    private long ts;

    @BeforeEach
    void setUp() {
        ts = System.nanoTime();

        Category cat = categoryRepository.save(Category.builder()
                .name("ExCat-" + ts).slug("excat-" + ts).level(1).sortOrder(0).isActive(true).build());
        Product product = productRepository.save(Product.builder()
                .name("Export Card " + ts).slug("exprod-" + ts).category(cat)
                .sortOrder(0).isActive(true).isOnSale(false).isNew(false)
                .isTrending(false).isBestSeller(false).isPreOrder(false).isFeatured(false).build());
        variant = variantRepository.save(ProductVariant.builder()
                .product(product).variantType("Box")
                .price(new BigDecimal("600.00")).isActive(true).build());

        var location = locationRepository.save(InventoryLocation.builder()
                .name("ExLoc-" + ts).isActive(true).build());
        inventoryService.setStock(variant.getId(), location.getId(), 10);

        admin = adminUserRepository.save(AdminUser.builder()
                .username("export-admin-" + ts)
                .password(passwordEncoder.encode("pw"))
                .role("ADMIN")
                .build());
    }

    private void createOrder() throws Exception {
        String token = "export-token-" + ts + "-" + UUID.randomUUID();
        var cart = cartRepository.save(Cart.builder().sessionToken(token).build());
        cartService.addToCart(cart, variant.getId(), 1);

        CheckoutRequest req = new CheckoutRequest();
        req.setIdempotencyKey(UUID.randomUUID().toString());
        req.setGuestName("Export Guest");
        req.setGuestEmail("export-" + ts + "@example.com");
        req.setShippingFirstName("Export");
        req.setShippingLastName("Guest");
        req.setShippingAddressLine1("100 Main St");
        req.setShippingCity("Boston");
        req.setShippingState("MA");
        req.setShippingZip("02101");
        req.setShippingCountry("US");
        req.setBillingSameAsShipping(true);

        mockMvc.perform(post("/api/checkout")
                        .with(csrf())
                        .cookie(new MockCookie("cart_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    // ── Test 1: Export contains allowed columns ───────────────────────────

    @Test
    void export_containsAllowedColumns() throws Exception {
        createOrder();

        var result = mockMvc.perform(get("/admin/api/orders/export")
                        .with(user(admin.getUsername()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn();

        String csv = result.getResponse().getContentAsString();
        assertThat(csv).contains("order_number");
        assertThat(csv).contains("status");
        assertThat(csv).contains("total");
        assertThat(csv).contains("customer_or_guest_name");
        assertThat(csv).contains("email");
        assertThat(csv).contains("shipping_state");
        assertThat(csv).contains("carrier");
        assertThat(csv).contains("tracking_number");
        assertThat(csv).contains("created_at");
        assertThat(csv).contains("subtotal");
        assertThat(csv).contains("shipping_amount");
        assertThat(csv).contains("tax_amount");
    }

    // ── Test 2: Export does NOT contain sensitive fields ─────────────────

    @Test
    void export_doesNotContainSensitiveFields() throws Exception {
        createOrder();

        var result = mockMvc.perform(get("/admin/api/orders/export")
                        .with(user(admin.getUsername()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn();

        String csv = result.getResponse().getContentAsString();
        assertThat(csv).doesNotContain("idempotency_key");
        assertThat(csv).doesNotContain("guest_cart_token_hash");
        assertThat(csv).doesNotContain("request_fingerprint");
        assertThat(csv).doesNotContain("provider_payment_id");
    }

    // ── Test 3: Export requires admin auth ───────────────────────────────

    @Test
    void export_requiresAdminAuth() throws Exception {
        // Anonymous → redirect to admin login
        mockMvc.perform(get("/admin/api/orders/export"))
                .andExpect(status().is3xxRedirection());
    }
}
