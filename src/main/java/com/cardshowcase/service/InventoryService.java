package com.cardshowcase.service;

import com.cardshowcase.exception.InsufficientStockException;
import com.cardshowcase.model.entity.Inventory;
import com.cardshowcase.model.entity.InventoryLocation;
import com.cardshowcase.model.entity.ProductVariant;
import com.cardshowcase.repository.InventoryLocationRepository;
import com.cardshowcase.repository.InventoryRepository;
import com.cardshowcase.repository.ProductVariantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepo;
    private final ProductVariantRepository variantRepo;
    private final InventoryLocationRepository locationRepo;

    /**
     * Self-reference injected via field (not constructor) so Spring calls
     * doAdjustStock through the AOP proxy, making @Transactional(REQUIRES_NEW) effective.
     */
    @Lazy
    @Autowired
    private InventoryService self;

    public int getTotalStock(Long variantId) {
        Integer total = inventoryRepo.getTotalStock(variantId);
        return total != null ? total : 0;
    }

    public int getStockByLocation(Long variantId, Long locationId) {
        return inventoryRepo.findByVariantIdAndLocationId(variantId, locationId)
                .map(Inventory::getQuantity)
                .orElse(0);
    }

    public List<Inventory> getInventoryByVariant(Long variantId) {
        return inventoryRepo.findByVariantId(variantId);
    }

    public List<Inventory> getLowStockItems() {
        return inventoryRepo.findLowStockItems();
    }

    public boolean isInStock(Long variantId) {
        return getTotalStock(variantId) > 0;
    }

    /**
     * Adjusts stock by quantityChange (positive = add, negative = subtract).
     * Retries up to 3 times on optimistic-lock conflicts; each attempt runs in
     * its own REQUIRES_NEW transaction via the self-proxy so @Transactional is applied.
     * Throws InsufficientStockException immediately (no retry) if stock would go negative.
     */
    public Inventory adjustStock(Long variantId, Long locationId, int quantityChange) {
        ObjectOptimisticLockingFailureException lastEx = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return self.doAdjustStock(variantId, locationId, quantityChange);
            } catch (ObjectOptimisticLockingFailureException ex) {
                log.warn("Optimistic lock conflict on inventory variantId={} attempt={}", variantId, attempt + 1);
                lastEx = ex;
            }
            // InsufficientStockException propagates immediately — no retry
        }
        throw lastEx;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Inventory doAdjustStock(Long variantId, Long locationId, int quantityChange) {
        Inventory inv = getOrCreate(variantId, locationId);
        int newQty = inv.getQuantity() + quantityChange;
        if (newQty < 0) {
            throw new InsufficientStockException(
                    "Insufficient stock: requested=" + Math.abs(quantityChange)
                    + " available=" + inv.getQuantity());
        }
        inv.setQuantity(newQty);
        return inventoryRepo.save(inv);
    }

    @Transactional
    public Inventory setStock(Long variantId, Long locationId, int quantity) {
        Inventory inv = getOrCreate(variantId, locationId);
        inv.setQuantity(Math.max(0, quantity));
        return inventoryRepo.save(inv);
    }

    /** Builds a nested map: variantId → locationId → quantity, for the product edit page inventory grid. */
    public Map<Long, Map<Long, Integer>> buildInventoryMap(List<Long> variantIds) {
        if (variantIds == null || variantIds.isEmpty()) return Map.of();
        List<Inventory> records = inventoryRepo.findByVariantIdIn(variantIds);
        Map<Long, Map<Long, Integer>> result = new HashMap<>();
        for (Inventory inv : records) {
            result.computeIfAbsent(inv.getVariant().getId(), k -> new HashMap<>())
                  .put(inv.getLocation().getId(), inv.getQuantity());
        }
        return result;
    }

    /** Total stock per variantId, for the product edit page variant table. */
    public Map<Long, Integer> getTotalStockByVariantIds(List<Long> variantIds) {
        if (variantIds == null || variantIds.isEmpty()) return Map.of();
        return inventoryRepo.sumStockByVariantIds(variantIds).stream()
                .collect(Collectors.toMap(r -> (Long) r[0], r -> ((Number) r[1]).intValue()));
    }

    /** Total stock per productId, for the admin product list page. */
    public Map<Long, Integer> getStockByProductIds(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) return Map.of();
        return inventoryRepo.sumStockByProductIds(productIds).stream()
                .collect(Collectors.toMap(r -> (Long) r[0], r -> ((Number) r[1]).intValue()));
    }

    private Inventory getOrCreate(Long variantId, Long locationId) {
        return inventoryRepo.findByVariantIdAndLocationId(variantId, locationId)
                .orElseGet(() -> {
                    ProductVariant variant = variantRepo.findById(variantId)
                            .orElseThrow(() -> new IllegalArgumentException("Variant not found: " + variantId));
                    InventoryLocation location = locationRepo.findById(locationId)
                            .orElseThrow(() -> new IllegalArgumentException("Location not found: " + locationId));
                    return Inventory.builder()
                            .variant(variant)
                            .location(location)
                            .quantity(0)
                            .build();
                });
    }
}
