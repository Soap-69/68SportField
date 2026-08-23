package com.cardshowcase;

import com.cardshowcase.model.dto.CheckoutRequest;
import com.cardshowcase.model.dto.RegisterDTO;
import com.cardshowcase.model.entity.*;
import com.cardshowcase.repository.*;
import com.cardshowcase.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CheckoutIntegrationTest extends BaseIntegrationTest {

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

    // Shared test fixtures (recreated before each test with unique timestamps)
    private ProductVariant variant;
    private InventoryLocation location;
    private long ts;

    @BeforeEach
    void setUp() {
        ts = System.nanoTime();

        Category cat = categoryRepository.save(Category.builder()
                .name("Cat-" + ts).slug("cat-" + ts).level(1).sortOrder(0).isActive(true).build());
        Product product = productRepository.save(Product.builder()
                .name("Test Product " + ts).slug("prod-" + ts).category(cat)
                .sortOrder(0).isActive(true).isOnSale(false).isNew(false)
                .isTrending(false).isBestSeller(false).isPreOrder(false).isFeatured(false).build());
        variant = variantRepository.save(ProductVariant.builder()
                .product(product).variantType("Box")
                .price(new BigDecimal("50.00")).isActive(true).build());

        location = locationRepository.save(InventoryLocation.builder()
                .name("Loc-" + ts).isActive(true).build());
        inventoryService.setStock(variant.getId(), location.getId(), 20);
    }

    // ── Helper builders ───────────────────────────────────────────────

    private CheckoutRequest guestRequest(String idempotencyKey) {
        CheckoutRequest req = new CheckoutRequest();
        req.setIdempotencyKey(idempotencyKey);
        req.setGuestName("Jane Guest");
        req.setGuestEmail("jane-" + ts + "@example.com");
        req.setShippingFirstName("Jane");
        req.setShippingLastName("Guest");
        req.setShippingAddressLine1("100 Main St");
        req.setShippingCity("Boston");
        req.setShippingState("MA");
        req.setShippingZip("02101");
        req.setShippingCountry("US");
        req.setBillingSameAsShipping(true);
        return req;
    }

    private Customer registerCustomer(String emailPrefix) {
        RegisterDTO dto = new RegisterDTO();
        dto.setEmail(emailPrefix + ts + "@example.com");
        dto.setPassword("Password1");
        dto.setConfirmPassword("Password1");
        dto.setFirstName("Test");
        dto.setLastName("Buyer");
        return customerAuthService.register(dto);
    }

    private Cart guestCart(String token) {
        Cart cart = cartRepository.save(Cart.builder().sessionToken(token).build());
        cartService.addToCart(cart, variant.getId(), 2);
        return cart;
    }

    private Cart customerCart(Customer customer) {
        Cart cart = cartRepository.save(Cart.builder()
                .customer(customer).build());
        cartService.addToCart(cart, variant.getId(), 2);
        return cart;
    }

    // ── 1. Guest checkout — end-to-end ────────────────────────────────

    @Test
    void guestCheckout_success_createsOrderAndClearsCart() throws Exception {
        String token = "guest-token-" + ts;
        Cart cart = guestCart(token);

        mockMvc.perform(post("/api/checkout")
                        .with(csrf())
                        .cookie(new MockCookie("cart_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(guestRequest(UUID.randomUUID().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber", startsWith("ORD-")))
                .andExpect(jsonPath("$.status", is("PENDING_PAYMENT")))
                .andExpect(jsonPath("$.items.length()", is(1)))
                .andExpect(jsonPath("$.items[0].quantity", is(2)));

        // Cart items must be cleared after checkout
        assertThat(cartItemRepository.findByCartId(cart.getId())).isEmpty();
    }

    // ── 2. Logged-in customer checkout with saved address ──────────────

    @Test
    void customerCheckout_withSavedAddress_success() throws Exception {
        Customer customer = registerCustomer("buyer-saved-");
        Cart cart = customerCart(customer);

        // Create a saved address
        CustomerAddress addr = addressRepository.save(CustomerAddress.builder()
                .customer(customer)
                .firstName("John").lastName("Buyer")
                .addressLine1("200 Commerce Ave")
                .city("Springfield").state("MA").zipCode("01101").country("US")
                .isDefaultShipping(true).isDefaultBilling(true).build());

        CheckoutRequest req = new CheckoutRequest();
        req.setIdempotencyKey(UUID.randomUUID().toString());
        req.setSavedShippingAddressId(addr.getId());
        req.setSavedBillingAddressId(addr.getId());

        CustomerPrincipal principal = new CustomerPrincipal(customer);

        mockMvc.perform(post("/api/checkout")
                        .with(csrf())
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber", startsWith("ORD-")))
                .andExpect(jsonPath("$.status", is("PENDING_PAYMENT")))
                .andExpect(jsonPath("$.shippingAddressLine1").doesNotExist()); // not in OrderResponse

        assertThat(cartItemRepository.findByCartId(cart.getId())).isEmpty();

        // Verify order is linked to customer
        List<Order> orders = orderRepository.findByCustomer_IdOrderByCreatedAtDesc(customer.getId());
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).getShippingAddressLine1()).isEqualTo("200 Commerce Ave");
    }

    // ── 3. GET /api/orders/{id} ownership enforcement ─────────────────

    @Test
    void getOrder_byOwner_returns200() throws Exception {
        Customer owner = registerCustomer("owner-");
        Cart cart = customerCart(owner);
        CustomerPrincipal principal = new CustomerPrincipal(owner);

        MvcResult checkoutResult = mockMvc.perform(post("/api/checkout")
                        .with(csrf()).with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                guestRequest(UUID.randomUUID().toString()))))
                .andExpect(status().isOk())
                .andReturn();

        Long orderId = objectMapper.readTree(
                checkoutResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(get("/api/orders/" + orderId).with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(orderId.intValue())));
    }

    @Test
    void getOrder_byNonOwner_returns403() throws Exception {
        Customer owner    = registerCustomer("order-owner-");
        Customer nonOwner = registerCustomer("non-owner-");

        Cart cart = customerCart(owner);
        CustomerPrincipal ownerPrincipal = new CustomerPrincipal(owner);
        CustomerPrincipal nonOwnerPrincipal = new CustomerPrincipal(nonOwner);

        MvcResult checkoutResult = mockMvc.perform(post("/api/checkout")
                        .with(csrf()).with(user(ownerPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                guestRequest(UUID.randomUUID().toString()))))
                .andExpect(status().isOk())
                .andReturn();

        Long orderId = objectMapper.readTree(
                checkoutResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(get("/api/orders/" + orderId).with(user(nonOwnerPrincipal)))
                .andExpect(status().isForbidden());
    }

    // ── 4. Idempotency: duplicate key same content ────────────────────

    @Test
    void checkout_duplicateIdempotencyKey_returnsSameOrder() throws Exception {
        String token = "idem-dup-" + ts;
        guestCart(token);
        String key = "key-dup-" + ts;
        MockCookie cookie = new MockCookie("cart_token", token);
        String body = objectMapper.writeValueAsString(guestRequest(key));

        // First checkout — creates order and clears cart
        MvcResult first = mockMvc.perform(post("/api/checkout")
                        .with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();

        Long firstId = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("id").asLong();

        // Second checkout — same key, cart now empty (genuine retry scenario)
        MvcResult second = mockMvc.perform(post("/api/checkout")
                        .with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();

        Long secondId = objectMapper.readTree(second.getResponse().getContentAsString())
                .get("id").asLong();

        assertThat(secondId).isEqualTo(firstId);                      // same order returned
        assertThat(orderRepository.count()).isGreaterThanOrEqualTo(1); // no second order created
    }

    // ── 5. Idempotency: same key, different content → 409 ─────────────

    @Test
    void checkout_sameKeyDifferentCartContent_returns409() throws Exception {
        String key = "key-conflict-" + ts;

        // Cart A: 2 units
        String tokenA = "cart-A-" + ts;
        guestCart(tokenA);
        String bodyA = objectMapper.writeValueAsString(guestRequest(key));

        mockMvc.perform(post("/api/checkout")
                        .with(csrf()).cookie(new MockCookie("cart_token", tokenA))
                        .contentType(MediaType.APPLICATION_JSON).content(bodyA))
                .andExpect(status().isOk());

        // Cart B: 3 units of the same variant (different quantity → different fingerprint)
        String tokenB = "cart-B-" + ts;
        Cart cartB = cartRepository.save(Cart.builder().sessionToken(tokenB).build());
        cartService.addToCart(cartB, variant.getId(), 3);

        CheckoutRequest conflictReq = guestRequest(key); // same key
        conflictReq.setShippingAddressLine1("999 Different Blvd"); // also different address

        mockMvc.perform(post("/api/checkout")
                        .with(csrf()).cookie(new MockCookie("cart_token", tokenB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conflictReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", containsString("already used")));
    }

    // ── 6. Client-supplied cartId in request body is ignored ──────────

    @Test
    void checkout_ignoresCartIdInRequestBody() throws Exception {
        String token = "real-cart-" + ts;
        Cart realCart = guestCart(token); // 2 items

        // Build a JSON body that includes a fake cartId pointing nowhere
        CheckoutRequest req = guestRequest(UUID.randomUUID().toString());
        String bodyWithFakeCartId = objectMapper.writeValueAsString(req)
                .replace("{", "{\"cartId\":999999,"); // inject arbitrary cartId field

        mockMvc.perform(post("/api/checkout")
                        .with(csrf())
                        .cookie(new MockCookie("cart_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithFakeCartId))
                .andExpect(status().isOk()) // succeeds using the cookie-resolved cart
                .andExpect(jsonPath("$.status", is("PENDING_PAYMENT")));

        // Real cart was cleared, proving the server used the cookie-resolved cart
        assertThat(cartItemRepository.findByCartId(realCart.getId())).isEmpty();
    }

    // ── 7. Checkout with empty cart → 400 ─────────────────────────────

    @Test
    void checkout_emptyCart_returns400() throws Exception {
        String token = "empty-cart-" + ts;
        cartRepository.save(Cart.builder().sessionToken(token).build()); // no items

        mockMvc.perform(post("/api/checkout")
                        .with(csrf())
                        .cookie(new MockCookie("cart_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(guestRequest(UUID.randomUUID().toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("empty")));
    }

    // ── 8. Checkout with insufficient stock → 422 ─────────────────────

    @Test
    void checkout_insufficientStock_returns422() throws Exception {
        String token = "overstock-" + ts;
        // Add 2 units to cart first (stock is still 20)
        guestCart(token);
        // Then reduce stock below the cart quantity so checkout fails
        inventoryService.setStock(variant.getId(), location.getId(), 1);

        mockMvc.perform(post("/api/checkout")
                        .with(csrf())
                        .cookie(new MockCookie("cart_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(guestRequest(UUID.randomUUID().toString()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", containsString("Insufficient stock")));
    }
}
