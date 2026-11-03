package com.cardshowcase.support;

import com.cardshowcase.model.dto.CheckoutRequest;
import com.cardshowcase.model.dto.RegisterDTO;
import com.cardshowcase.model.entity.*;
import com.cardshowcase.repository.*;
import com.cardshowcase.service.CartService;
import com.cardshowcase.service.CustomerAuthService;
import com.cardshowcase.service.CustomerPrincipal;
import com.cardshowcase.service.InventoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared setup for the Week 8 integration tests: a product, a variant, inventory,
 * two admins, and MockMvc helpers that drive the real HTTP endpoints.
 */
@Component
public class Week8Fixture {

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
    @Autowired CustomerAuthService customerAuthService;
    @Autowired NotificationLogRepository notificationLogRepository;

    public ProductVariant variant;
    public InventoryLocation location;
    public AdminUser seniorAdmin;
    public AdminUser regularAdmin;
    public long ts;

    public void prepare(BigDecimal price, int stock) {
        ts = System.nanoTime();
        Category cat = categoryRepository.save(Category.builder()
                .name("W8Cat-" + ts).slug("w8-cat-" + ts).level(1).sortOrder(0).isActive(true).build());
        Product product = productRepository.save(Product.builder()
                .name("Week8 Card " + ts).slug("w8-prod-" + ts).category(cat)
                .sortOrder(0).isActive(true).isOnSale(false).isNew(false)
                .isTrending(false).isBestSeller(false).isPreOrder(false).isFeatured(false).build());
        variant = variantRepository.save(ProductVariant.builder()
                .product(product).variantType("Box").sku("W8-SKU-" + ts)
                .price(price).isActive(true).build());
        location = locationRepository.save(InventoryLocation.builder()
                .name("W8Loc-" + ts).isActive(true).build());
        inventoryService.setStock(variant.getId(), location.getId(), stock);
        seniorAdmin = adminUserRepository.save(AdminUser.builder()
                .username("w8-senior-" + ts).password(passwordEncoder.encode("pw")).role("SENIOR_ADMIN").build());
        regularAdmin = adminUserRepository.save(AdminUser.builder()
                .username("w8-admin-" + ts).password(passwordEncoder.encode("pw")).role("ADMIN").build());
    }

    public Customer registerCustomer(String prefix) {
        RegisterDTO dto = new RegisterDTO();
        dto.setEmail(prefix + "-" + ts + "@example.com");
        dto.setPassword("Password1");
        dto.setConfirmPassword("Password1");
        dto.setFirstName("Casey");
        dto.setLastName("Buyer");
        return customerAuthService.register(dto);
    }

    public CheckoutRequest guestRequest(String state, String idempotencyKey) {
        CheckoutRequest req = new CheckoutRequest();
        req.setIdempotencyKey(idempotencyKey);
        req.setGuestName("Guest Buyer");
        req.setGuestEmail("guest-" + ts + "@example.com");
        fillAddress(req, state);
        return req;
    }

    public CheckoutRequest customerRequest(String state) {
        CheckoutRequest req = new CheckoutRequest();
        req.setIdempotencyKey(UUID.randomUUID().toString());
        fillAddress(req, state);
        return req;
    }

    private static void fillAddress(CheckoutRequest req, String state) {
        req.setShippingFirstName("Pat");
        req.setShippingLastName("Recipient");
        req.setShippingAddressLine1("100 Main St");
        req.setShippingCity("Boston");
        req.setShippingState(state);
        req.setShippingZip("02101");
        req.setShippingCountry("US");
        req.setBillingSameAsShipping(true);
    }

    public String newGuestToken() {
        return "w8-token-" + ts + "-" + UUID.randomUUID();
    }

    /** Guest checkout through POST /api/checkout; returns the order id. */
    public long guestCheckout(MockMvc mockMvc, String state, int qty, String idempotencyKey) throws Exception {
        return guestCheckout(mockMvc, state, qty, idempotencyKey, newGuestToken());
    }

    /** Guest checkout with an explicit cart token, so a replay can present the same identity. */
    public long guestCheckout(MockMvc mockMvc, String state, int qty, String idempotencyKey, String token) throws Exception {
        Cart cart = cartRepository.save(Cart.builder().sessionToken(token).build());
        cartService.addToCart(cart, variant.getId(), qty);
        MvcResult result = mockMvc.perform(post("/api/checkout")
                        .with(csrf())
                        .cookie(new MockCookie("cart_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(guestRequest(state, idempotencyKey))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    /** Replays a guest checkout with the same key; returns the HTTP status and order id (or -1). */
    public MvcResult guestCheckoutReplay(MockMvc mockMvc, String state, String idempotencyKey, String token) throws Exception {
        return mockMvc.perform(post("/api/checkout")
                        .with(csrf())
                        .cookie(new MockCookie("cart_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(guestRequest(state, idempotencyKey))))
                .andReturn();
    }

    /** Logged-in customer checkout; returns the order id. */
    public long customerCheckout(MockMvc mockMvc, Customer customer, String state, int qty) throws Exception {
        // One cart per customer: checkout clears items but keeps the cart row.
        Cart cart = cartRepository.findByCustomerId(customer.getId())
                .orElseGet(() -> cartRepository.save(Cart.builder().customer(customer).build()));
        cartService.addToCart(cart, variant.getId(), qty);
        MvcResult result = mockMvc.perform(post("/api/checkout")
                        .with(csrf())
                        .with(user(new CustomerPrincipal(customer)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(customerRequest(state))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    public MvcResult manualConfirm(MockMvc mockMvc, long orderId, String outcome, String key) throws Exception {
        return mockMvc.perform(adminPost("/admin/api/orders/" + orderId + "/payments/manual-confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", outcome, "idempotencyKey", key))))
                .andReturn();
    }

    public long paidGuestOrder(MockMvc mockMvc, String state, int qty) throws Exception {
        long orderId = guestCheckout(mockMvc, state, qty, UUID.randomUUID().toString());
        MvcResult r = manualConfirm(mockMvc, orderId, "SUCCESS", UUID.randomUUID().toString());
        if (r.getResponse().getStatus() != 200) {
            throw new IllegalStateException("manual-confirm failed: " + r.getResponse().getContentAsString());
        }
        return orderId;
    }

    public MockHttpServletRequestBuilder adminPost(String url) {
        return post(url)
                .with(user(seniorAdmin.getUsername()).roles("SENIOR_ADMIN"))
                .with(csrf());
    }

    public MvcResult adminJson(MockMvc mockMvc, String url, Map<String, ?> body) throws Exception {
        return mockMvc.perform(adminPost(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    public MvcResult adminPlain(MockMvc mockMvc, String url) throws Exception {
        return mockMvc.perform(adminPost(url)).andReturn();
    }

    public Order order(long orderId) {
        return orderRepository.findById(orderId).orElseThrow();
    }

    public NotificationLog log(long orderId, NotificationType type) {
        return notificationLogRepository.findByOrder_IdAndType(orderId, type).orElseThrow();
    }

    /** Waits on the row leaving PENDING/SENDING (a completion condition, never a fixed sleep). */
    public NotificationLog awaitSettled(long orderId, NotificationType type) {
        await().atMost(Duration.ofSeconds(15)).until(() ->
                notificationLogRepository.findByOrder_IdAndType(orderId, type)
                        .map(n -> n.getStatus() != NotificationStatus.PENDING
                                && n.getStatus() != NotificationStatus.SENDING)
                        .orElse(false));
        return log(orderId, type);
    }

    public NotificationLog awaitStatus(long logId, NotificationStatus expected) {
        await().atMost(Duration.ofSeconds(15)).until(() ->
                notificationLogRepository.findById(logId).map(n -> n.getStatus() == expected).orElse(false));
        return notificationLogRepository.findById(logId).orElseThrow();
    }
}
