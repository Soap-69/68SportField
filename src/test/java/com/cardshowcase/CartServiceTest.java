package com.cardshowcase;

import com.cardshowcase.model.entity.*;
import com.cardshowcase.repository.*;
import com.cardshowcase.service.CartService;
import com.cardshowcase.service.CustomerAuthService;
import com.cardshowcase.model.dto.RegisterDTO;
import com.cardshowcase.exception.InsufficientStockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class CartServiceTest extends BaseIntegrationTest {

    @Autowired CartService cartService;
    @Autowired CartRepository cartRepository;
    @Autowired CartItemRepository cartItemRepository;
    @Autowired ProductVariantRepository variantRepository;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired CustomerAuthService customerAuthService;
    @Autowired InventoryLocationRepository locationRepository;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired com.cardshowcase.service.InventoryService inventoryService;

    private ProductVariant variant;
    private InventoryLocation location;
    private Cart guestCart;

    @BeforeEach
    void setUp() {
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        inventoryRepository.deleteAll();
        customerRepository.deleteAll();

        long ts = System.nanoTime();
        Category cat = categoryRepository.save(Category.builder()
                .name("Test").slug("test-" + ts).level(1).sortOrder(0).isActive(true).build());
        Product product = productRepository.save(Product.builder()
                .name("Test Product").slug("test-prod-" + ts).category(cat)
                .sortOrder(0).isActive(true).isOnSale(false).isNew(false)
                .isTrending(false).isBestSeller(false).isPreOrder(false).isFeatured(false).build());
        variant = variantRepository.save(ProductVariant.builder()
                .product(product).variantType("Box").price(new BigDecimal("50.00")).isActive(true).build());

        location = locationRepository.save(InventoryLocation.builder()
                .name("Test Warehouse " + ts).isActive(true).build());
        inventoryService.setStock(variant.getId(), location.getId(), 10);

        guestCart = cartRepository.save(Cart.builder().sessionToken("test-session-" + ts).build());
    }

    @Test
    void addToCart_success() {
        Cart cart = cartService.addToCart(guestCart, variant.getId(), 2);
        List<CartItem> items = cartItemRepository.findByCartId(cart.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getQuantity()).isEqualTo(2);
    }

    @Test
    void addToCart_newItem_persistsRequestedQuantity() {
        // Regression guard: the CartItem must be persisted with the requested quantity,
        // not with an intermediate quantity=0 that violates the cart_items CHECK constraint.
        Cart cart = cartService.addToCart(guestCart, variant.getId(), 6);
        CartItem saved = cartItemRepository.findByCartId(cart.getId()).get(0);
        assertThat(saved.getQuantity()).isEqualTo(6);
    }

    @Test
    void addToCart_existingItem_quantityIncreases() {
        cartService.addToCart(guestCart, variant.getId(), 2);
        cartService.addToCart(guestCart, variant.getId(), 3);
        List<CartItem> items = cartItemRepository.findByCartId(guestCart.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getQuantity()).isEqualTo(5);
    }

    @Test
    void addToCart_insufficientStock_throwsException() {
        assertThatThrownBy(() -> cartService.addToCart(guestCart, variant.getId(), 99))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void addToCart_zeroQuantity_throwsIllegalArgument() {
        assertThatThrownBy(() -> cartService.addToCart(guestCart, variant.getId(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
    }

    @Test
    void addToCart_negativeQuantity_throwsIllegalArgument() {
        assertThatThrownBy(() -> cartService.addToCart(guestCart, variant.getId(), -5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
    }

    @Test
    void removeItem_success() {
        Cart cart = cartService.addToCart(guestCart, variant.getId(), 1);
        CartItem item = cartItemRepository.findByCartId(cart.getId()).get(0);
        cartService.removeItem(cart, item.getId());
        assertThat(cartItemRepository.findByCartId(cart.getId())).isEmpty();
    }

    @Test
    void updateQuantity_success() {
        Cart cart = cartService.addToCart(guestCart, variant.getId(), 2);
        CartItem item = cartItemRepository.findByCartId(cart.getId()).get(0);
        cartService.updateItemQuantity(cart, item.getId(), 5);
        assertThat(cartItemRepository.findById(item.getId()).get().getQuantity()).isEqualTo(5);
    }

    @Test
    void updateQuantity_zeroRemovesItem() {
        Cart cart = cartService.addToCart(guestCart, variant.getId(), 2);
        CartItem item = cartItemRepository.findByCartId(cart.getId()).get(0);
        cartService.updateItemQuantity(cart, item.getId(), 0);
        assertThat(cartItemRepository.findByCartId(cart.getId())).isEmpty();
    }

    @Test
    void mergeGuestCart_combinesCorrectly() {
        cartService.addToCart(guestCart, variant.getId(), 3);

        RegisterDTO dto = new RegisterDTO();
        dto.setEmail("merge-test-" + System.nanoTime() + "@example.com");
        dto.setPassword("Password1"); dto.setConfirmPassword("Password1");
        dto.setFirstName("Test"); dto.setLastName("User");
        Customer customer = customerAuthService.register(dto);

        String sessionToken = guestCart.getSessionToken();
        cartService.mergeGuestCartOnLogin(sessionToken, customer.getId());

        Cart merged = cartRepository.findByCustomerId(customer.getId()).orElseThrow();
        List<CartItem> items = cartItemRepository.findByCartId(merged.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getQuantity()).isEqualTo(3);
    }
}
