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

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OrderFulfillmentReadinessTest extends BaseIntegrationTest {

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
    @Autowired OrderService orderService;
    @Autowired ShipmentRepository shipmentRepository;
    @Autowired ShippingService shippingService;

    private ProductVariant freeShippingVariant;
    private ProductVariant akHiVariant;
    private long ts;

    @BeforeEach
    void setUp() {
        ts = System.nanoTime();

        Category cat = categoryRepository.save(Category.builder()
                .name("FRCat-" + ts).slug("fr-cat-" + ts).level(1).sortOrder(0).isActive(true).build());

        // Variant priced at $600 → subtotal >= $500 → NOT_REQUIRED for continental US
        Product freeShipProduct = productRepository.save(Product.builder()
                .name("FreeShip Card " + ts).slug("free-ship-" + ts).category(cat)
                .sortOrder(0).isActive(true).isOnSale(false).isNew(false)
                .isTrending(false).isBestSeller(false).isPreOrder(false).isFeatured(false).build());
        freeShippingVariant = variantRepository.save(ProductVariant.builder()
                .product(freeShipProduct).variantType("Box")
                .price(new BigDecimal("600.00")).isActive(true).build());
        InventoryLocation locFree = locationRepository.save(InventoryLocation.builder()
                .name("FRLoc-" + ts).isActive(true).build());
        inventoryService.setStock(freeShippingVariant.getId(), locFree.getId(), 20);

        // Variant priced at $50 → used for AK/HI orders (QUOTE_REQUIRED)
        Product akHiProduct = productRepository.save(Product.builder()
                .name("AKHI Card " + ts).slug("akhi-" + ts).category(cat)
                .sortOrder(0).isActive(true).isOnSale(false).isNew(false)
                .isTrending(false).isBestSeller(false).isPreOrder(false).isFeatured(false).build());
        akHiVariant = variantRepository.save(ProductVariant.builder()
                .product(akHiProduct).variantType("Pack")
                .price(new BigDecimal("50.00")).isActive(true).build());
        InventoryLocation locAkHi = locationRepository.save(InventoryLocation.builder()
                .name("AKHILoc-" + ts).isActive(true).build());
        inventoryService.setStock(akHiVariant.getId(), locAkHi.getId(), 20);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Order checkoutAndPay(ProductVariant v, String state, String tokenSuffix) throws Exception {
        String token = "fr-tok-" + ts + "-" + tokenSuffix;
        var cart = cartRepository.save(Cart.builder().sessionToken(token).build());
        cartService.addToCart(cart, v.getId(), 1);

        CheckoutRequest req = new CheckoutRequest();
        req.setIdempotencyKey(UUID.randomUUID().toString());
        req.setGuestName("Test Guest");
        req.setGuestEmail("fr-" + ts + "-" + tokenSuffix + "@example.com");
        req.setShippingFirstName("Test");
        req.setShippingLastName("Guest");
        req.setShippingAddressLine1("100 Main St");
        req.setShippingCity("Anytown");
        req.setShippingState(state);
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
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("outcome", "SUCCESS", "idempotencyKey", UUID.randomUUID().toString()))))
                .andExpect(status().isOk());

        return orderRepository.findById(orderId).orElseThrow();
    }

    // ── A: PAID + NOT_REQUIRED → markProcessing succeeds ────────────────────

    @Test
    void A_paid_notRequired_markProcessingSucceeds() throws Exception {
        Order order = checkoutAndPay(freeShippingVariant, "MA", "a");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);

        Shipment shipment = shipmentRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(shipment.getShippingPaymentStatus()).isEqualTo(ShippingPaymentStatus.NOT_REQUIRED);

        Order result = orderService.markProcessing(order.getId());
        assertThat(result.getStatus()).isEqualTo(OrderStatus.PROCESSING);
    }

    // ── B: PAID + QUOTE_REQUIRED → markProcessing throws, order remains PAID ─

    @Test
    void B_paid_quoteRequired_markProcessingThrows() throws Exception {
        Order order = checkoutAndPay(akHiVariant, "AK", "b");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);

        Shipment shipment = shipmentRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(shipment.getShippingPaymentStatus()).isEqualTo(ShippingPaymentStatus.QUOTE_REQUIRED);

        assertThatThrownBy(() -> orderService.markProcessing(order.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("supplemental shipping obligation not yet resolved");

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    // ── C: PAID + QUOTED → markProcessing throws, order remains PAID ────────

    @Test
    void C_paid_quoted_markProcessingThrows() throws Exception {
        Order order = checkoutAndPay(akHiVariant, "HI", "c");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);

        shippingService.recordQuote(order.getId(), new BigDecimal("75.00"));

        Shipment shipment = shipmentRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(shipment.getShippingPaymentStatus()).isEqualTo(ShippingPaymentStatus.QUOTED);

        assertThatThrownBy(() -> orderService.markProcessing(order.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("supplemental shipping obligation not yet resolved");

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    // ── D: PAID + PAYMENT_PENDING → markProcessing throws, order remains PAID ─

    @Test
    void D_paid_paymentPending_markProcessingThrows() throws Exception {
        Order order = checkoutAndPay(akHiVariant, "AK", "d");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);

        shippingService.recordQuote(order.getId(), new BigDecimal("90.00"));
        shippingService.requestShippingPayment(order.getId());

        Shipment shipment = shipmentRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(shipment.getShippingPaymentStatus()).isEqualTo(ShippingPaymentStatus.PAYMENT_PENDING);

        assertThatThrownBy(() -> orderService.markProcessing(order.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("supplemental shipping obligation not yet resolved");

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    // ── E: PAID + PAID supplemental shipment → markProcessing succeeds ───────

    @Test
    void E_paid_supplementalPaid_markProcessingSucceeds() throws Exception {
        Order order = checkoutAndPay(akHiVariant, "HI", "e");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);

        shippingService.recordQuote(order.getId(), new BigDecimal("120.00"));
        shippingService.requestShippingPayment(order.getId());
        shippingService.confirmShippingPaymentReceived(order.getId());

        Shipment shipment = shipmentRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(shipment.getShippingPaymentStatus()).isEqualTo(ShippingPaymentStatus.PAID);

        Order result = orderService.markProcessing(order.getId());
        assertThat(result.getStatus()).isEqualTo(OrderStatus.PROCESSING);
    }

    // ── F: PAID + WAIVED → markProcessing succeeds ───────────────────────────

    @Test
    void F_paid_waived_markProcessingSucceeds() throws Exception {
        Order order = checkoutAndPay(akHiVariant, "AK", "f");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);

        // Advance to QUOTED then directly set to WAIVED via repository (no waive endpoint exists yet)
        shippingService.recordQuote(order.getId(), new BigDecimal("50.00"));
        Shipment shipment = shipmentRepository.findByOrder_Id(order.getId()).orElseThrow();
        shipment.setShippingPaymentStatus(ShippingPaymentStatus.WAIVED);
        shipmentRepository.save(shipment);

        Shipment reloadedShipment = shipmentRepository.findByOrder_Id(order.getId()).orElseThrow();
        assertThat(reloadedShipment.getShippingPaymentStatus()).isEqualTo(ShippingPaymentStatus.WAIVED);

        Order result = orderService.markProcessing(order.getId());
        assertThat(result.getStatus()).isEqualTo(OrderStatus.PROCESSING);
    }
}
