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

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the order-detail page (/account/orders/{id}) shipping row.
 *
 * Verifies that the correct shipping text is displayed for each ShippingPaymentStatus
 * variant, replacing the old "Calculated at fulfillment" placeholder.
 */
class OrderDetailPageTest extends BaseIntegrationTest {

    @Autowired ObjectMapper objectMapper;
    @Autowired CartService cartService;
    @Autowired CartRepository cartRepository;
    @Autowired ProductRepository productRepository;
    @Autowired ProductVariantRepository variantRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired InventoryLocationRepository locationRepository;
    @Autowired InventoryService inventoryService;
    @Autowired CustomerAuthService customerAuthService;
    @Autowired ShippingService shippingService;

    /** $50 variant — 1 item = $50 subtotal (Ground < $500 → QUOTE_REQUIRED) */
    private ProductVariant cheapVariant;
    /** $600 variant — 1 item = $600 subtotal (Ground ≥ $500 → NOT_REQUIRED, Free) */
    private ProductVariant expensiveVariant;
    private long ts;

    @BeforeEach
    void setUp() {
        ts = System.nanoTime();

        Category cat = categoryRepository.save(Category.builder()
                .name("ODPCat-" + ts).slug("odp-cat-" + ts)
                .level(1).sortOrder(0).isActive(true).build());

        Product cheap = productRepository.save(Product.builder()
                .name("ODP-Cheap-" + ts).slug("odp-cheap-" + ts).category(cat)
                .sortOrder(0).isActive(true).isOnSale(false).isNew(false)
                .isTrending(false).isBestSeller(false).isPreOrder(false).isFeatured(false).build());
        cheapVariant = variantRepository.save(ProductVariant.builder()
                .product(cheap).variantType("Box")
                .price(new BigDecimal("50.00")).isActive(true).build());

        Product expensive = productRepository.save(Product.builder()
                .name("ODP-Exp-" + ts).slug("odp-exp-" + ts).category(cat)
                .sortOrder(0).isActive(true).isOnSale(false).isNew(false)
                .isTrending(false).isBestSeller(false).isPreOrder(false).isFeatured(false).build());
        expensiveVariant = variantRepository.save(ProductVariant.builder()
                .product(expensive).variantType("Box")
                .price(new BigDecimal("600.00")).isActive(true).build());

        InventoryLocation loc = locationRepository.save(
                InventoryLocation.builder().name("ODPLoc-" + ts).isActive(true).build());
        inventoryService.setStock(cheapVariant.getId(), loc.getId(), 50);
        inventoryService.setStock(expensiveVariant.getId(), loc.getId(), 10);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Customer registerCustomer(String prefix) {
        RegisterDTO dto = new RegisterDTO();
        dto.setEmail(prefix + ts + "@example.com");
        dto.setPassword("Password1");
        dto.setConfirmPassword("Password1");
        dto.setFirstName("Test");
        dto.setLastName("Buyer");
        return customerAuthService.register(dto);
    }

    private long placeCustomerOrder(Customer customer, ProductVariant variant,
                                    String state, ServiceLevel serviceLevel) throws Exception {
        Cart cart = cartRepository.save(Cart.builder().customer(customer).build());
        cartService.addToCart(cart, variant.getId(), 1);

        CheckoutRequest req = new CheckoutRequest();
        req.setIdempotencyKey(UUID.randomUUID().toString());
        req.setShippingFirstName("Test");
        req.setShippingLastName("Buyer");
        req.setShippingAddressLine1("1 Test St");
        req.setShippingCity("Testville");
        req.setShippingState(state);
        req.setShippingZip("00000");
        req.setShippingCountry("US");
        req.setBillingSameAsShipping(true);
        req.setServiceLevel(serviceLevel);

        String json = mockMvc.perform(post("/api/checkout")
                        .with(csrf())
                        .with(user(new CustomerPrincipal(customer)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(json).get("id").asLong();
    }

    private void confirmPayment(long orderId) throws Exception {
        mockMvc.perform(post("/admin/api/orders/" + orderId + "/payments/manual-confirm")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "outcome", "SUCCESS",
                                "idempotencyKey", UUID.randomUUID().toString()))))
                .andExpect(status().isOk());
    }

    /** Fetches the order detail page and asserts each fragment appears in the HTML body. */
    private void assertDetailContains(Customer customer, long orderId, String... fragments)
            throws Exception {
        var result = mockMvc.perform(
                        get("/account/orders/" + orderId)
                                .with(user(new CustomerPrincipal(customer))))
                .andExpect(status().isOk());
        for (String fragment : fragments) {
            result.andExpect(content().string(containsString(fragment)));
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────

    /**
     * Ground ≥ $500 → NOT_REQUIRED, shippingAmount=$0.
     * Shipping row must show "Free".
     */
    @Test
    void orderDetail_groundFreeShipping_showsFree() throws Exception {
        Customer customer = registerCustomer("odp-free-");
        long orderId = placeCustomerOrder(customer, expensiveVariant, "MA", ServiceLevel.GROUND);

        assertDetailContains(customer, orderId, "Free");
    }

    /**
     * Ground < $500 → QUOTE_REQUIRED at checkout.
     * Shipping row must show "Shipping quote pending".
     */
    @Test
    void orderDetail_groundQuoteRequired_showsPending() throws Exception {
        Customer customer = registerCustomer("odp-qreq-");
        long orderId = placeCustomerOrder(customer, cheapVariant, "NY", ServiceLevel.GROUND);

        assertDetailContains(customer, orderId, "Shipping quote pending");
    }

    /**
     * Next Day Air → NOT_REQUIRED, shippingAmount=$150.
     * Shipping row must show the surcharge amount.
     */
    @Test
    void orderDetail_nextDayAir_showsSurcharge() throws Exception {
        Customer customer = registerCustomer("odp-nda-");
        long orderId = placeCustomerOrder(customer, cheapVariant, "FL", ServiceLevel.NEXT_DAY_AIR);

        assertDetailContains(customer, orderId, "$150.00");
    }

    /**
     * AK order: after the order is paid the Shipment is created with QUOTE_REQUIRED.
     * Shipping row must show "Shipping quote pending".
     */
    @Test
    void orderDetail_akHiAfterPayment_showsQuotePending() throws Exception {
        Customer customer = registerCustomer("odp-ak-");
        long orderId = placeCustomerOrder(customer, cheapVariant, "AK", ServiceLevel.GROUND);

        confirmPayment(orderId); // triggers AK/HI Shipment creation

        assertDetailContains(customer, orderId, "Shipping quote pending");
    }

    /**
     * After admin records a quote (auto-transitions to PAYMENT_PENDING):
     * shipping row must show "Awaiting payment" and the quoted amount.
     */
    @Test
    void orderDetail_afterQuote_showsAwaitingPayment() throws Exception {
        Customer customer = registerCustomer("odp-quoted-");
        long orderId = placeCustomerOrder(customer, cheapVariant, "TX", ServiceLevel.GROUND);

        confirmPayment(orderId);
        shippingService.recordQuote(orderId, new BigDecimal("12.50"));
        shippingService.requestShippingPayment(orderId);

        assertDetailContains(customer, orderId, "Awaiting payment", "12.50");
    }

    /**
     * After supplemental shipping confirmed PAID:
     * shipping row must show the final quoted amount.
     */
    @Test
    void orderDetail_shippingPaid_showsFinalAmount() throws Exception {
        Customer customer = registerCustomer("odp-spaid-");
        long orderId = placeCustomerOrder(customer, cheapVariant, "CA", ServiceLevel.GROUND);

        confirmPayment(orderId);
        shippingService.recordQuote(orderId, new BigDecimal("18.75"));
        shippingService.requestShippingPayment(orderId);
        shippingService.confirmShippingPaymentReceived(orderId);

        assertDetailContains(customer, orderId, "$18.75");
    }
}
