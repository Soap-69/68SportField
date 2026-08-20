package com.cardshowcase.service;

import com.cardshowcase.model.entity.*;
import com.cardshowcase.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the @Version-based optimistic locking in Inventory prevents
 * stock from going negative under concurrent deductions.
 *
 * Uses H2 (test profile, Flyway disabled, ddl-auto=create-drop).
 * Each setUp() creates uniquely-slugged entities via nanoTime so data from
 * previous runs does not interfere. @DirtiesContext is intentionally absent
 * to avoid dropping the shared H2 schema mid-suite (which would break other
 * test contexts that use the same mem:testdb database).
 */
@SpringBootTest
@ActiveProfiles("test")
class InventoryServiceConcurrencyTest {

    @Autowired private InventoryService         inventoryService;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private InventoryLocationRepository locationRepository;
    @Autowired private ProductRepository        productRepository;
    @Autowired private CategoryRepository       categoryRepository;

    private Long variantId;
    private Long locationId;

    /**
     * Creates committed test data before each test.
     * No @Transactional here — each repository.save() auto-commits so the
     * spawned threads can see the rows immediately.
     */
    @BeforeEach
    void setUp() {
        // Use nanoTime suffix so parallel test-class runs don't collide on unique slugs
        long ts = System.nanoTime();

        Category category = categoryRepository.save(
                Category.builder()
                        .name("Test Sport")
                        .slug("test-sport-" + ts)
                        .level(1)
                        .sortOrder(0)
                        .isActive(true)
                        .build());

        Product product = productRepository.save(
                Product.builder()
                        .name("Concurrency Test Product")
                        .slug("concurrency-test-" + ts)
                        .category(category)
                        .sortOrder(0)
                        .isActive(true)
                        .isOnSale(false)
                        .isNew(false)
                        .isTrending(false)
                        .isBestSeller(false)
                        .isPreOrder(false)
                        .isFeatured(false)
                        .build());

        ProductVariant variant = variantRepository.save(
                ProductVariant.builder()
                        .product(product)
                        .variantType("BOX")
                        .price(new BigDecimal("100.00"))
                        .isActive(true)
                        .build());
        variantId = variant.getId();

        InventoryLocation location = locationRepository.save(
                InventoryLocation.builder()
                        .name("Concurrency Test Warehouse")
                        .isActive(true)
                        .build());
        locationId = location.getId();

        // Commit initial stock = 10 via service (its own @Transactional)
        inventoryService.setStock(variantId, locationId, 10);
    }

    @Test
    void concurrentStockAdjustmentShouldNotOversell() throws InterruptedException {
        final int threadCount   = 20;
        final int initialStock  = 10;

        ExecutorService executor      = Executors.newFixedThreadPool(threadCount);
        CountDownLatch  latch         = new CountDownLatch(threadCount);
        AtomicInteger   successCount  = new AtomicInteger(0);
        AtomicInteger   failCount     = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    inventoryService.adjustStock(variantId, locationId, -1);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // InsufficientStockException or exhausted-retry OptimisticLockException
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).as("all threads should finish within 30s").isTrue();

        int finalStock = inventoryService.getTotalStock(variantId);

        // Stock must never go negative
        assertThat(finalStock)
                .as("final stock must not be negative")
                .isGreaterThanOrEqualTo(0);

        // Can't sell more units than were available
        assertThat(successCount.get())
                .as("successful deductions must not exceed initial stock")
                .isLessThanOrEqualTo(initialStock);

        // Every thread must have reported success or failure
        assertThat(successCount.get() + failCount.get())
                .as("success + fail must equal total threads")
                .isEqualTo(threadCount);

        // Conservation: stock removed == units sold
        assertThat(finalStock)
                .as("final stock = initial - successful deductions")
                .isEqualTo(initialStock - successCount.get());
    }
}
