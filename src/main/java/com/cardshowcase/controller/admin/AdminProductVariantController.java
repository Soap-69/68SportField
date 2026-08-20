package com.cardshowcase.controller.admin;

import com.cardshowcase.model.dto.ProductVariantDTO;
import com.cardshowcase.service.InventoryService;
import com.cardshowcase.service.ProductVariantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.Map;

@Controller
@RequestMapping("/admin/products")
@RequiredArgsConstructor
public class AdminProductVariantController {

    private final ProductVariantService variantService;
    private final InventoryService inventoryService;

    // ── Variant CRUD ──────────────────────────────────────────────────────────

    @PostMapping("/{productId}/variants/create")
    public String createVariant(@PathVariable Long productId,
                                @RequestParam String variantType,
                                @RequestParam(required = false) String sku,
                                @RequestParam BigDecimal price,
                                @RequestParam(required = false) BigDecimal salePrice,
                                @RequestParam(required = false) BigDecimal weight,
                                @RequestParam(required = false) MultipartFile imageFile,
                                @RequestParam(value = "isActive", required = false) String isActiveStr,
                                RedirectAttributes flash) {
        try {
            ProductVariantDTO dto = new ProductVariantDTO();
            dto.setVariantType(variantType);
            dto.setSku(sku);
            dto.setPrice(price);
            dto.setSalePrice(salePrice);
            dto.setWeight(weight);
            dto.setIsActive("true".equals(isActiveStr));
            variantService.createVariant(productId, dto, imageFile);
            flash.addFlashAttribute("successMessage", variantType + " variant created.");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/products/" + productId + "/edit";
    }

    @PostMapping("/{productId}/variants/{variantId}/edit")
    public String editVariant(@PathVariable Long productId,
                              @PathVariable Long variantId,
                              @RequestParam(required = false) String sku,
                              @RequestParam BigDecimal price,
                              @RequestParam(required = false) BigDecimal salePrice,
                              @RequestParam(required = false) BigDecimal weight,
                              @RequestParam(required = false) MultipartFile imageFile,
                              @RequestParam(value = "isActive", required = false) String isActiveStr,
                              RedirectAttributes flash) {
        try {
            ProductVariantDTO dto = new ProductVariantDTO();
            dto.setSku(sku);
            dto.setPrice(price);
            dto.setSalePrice(salePrice);
            dto.setWeight(weight);
            dto.setIsActive("true".equals(isActiveStr));
            variantService.updateVariant(variantId, dto, imageFile);
            flash.addFlashAttribute("successMessage", "Variant updated.");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/products/" + productId + "/edit";
    }

    @PostMapping("/{productId}/variants/{variantId}/delete")
    public String deleteVariant(@PathVariable Long productId,
                                @PathVariable Long variantId,
                                RedirectAttributes flash) {
        try {
            variantService.deleteVariant(variantId);
            flash.addFlashAttribute("successMessage", "Variant deleted.");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/products/" + productId + "/edit";
    }

    // ── Inventory bulk update ─────────────────────────────────────────────────

    @PostMapping("/{productId}/inventory/update")
    public String updateInventory(@PathVariable Long productId,
                                  @RequestParam Map<String, String> allParams,
                                  RedirectAttributes flash) {
        try {
            allParams.entrySet().stream()
                    .filter(e -> e.getKey().startsWith("inv_"))
                    .forEach(e -> {
                        String[] parts = e.getKey().split("_");
                        if (parts.length == 3) {
                            Long variantId  = Long.parseLong(parts[1]);
                            Long locationId = Long.parseLong(parts[2]);
                            int quantity    = Integer.parseInt(e.getValue().isBlank() ? "0" : e.getValue());
                            inventoryService.setStock(variantId, locationId, quantity);
                        }
                    });
            flash.addFlashAttribute("successMessage", "Inventory updated successfully.");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", "Failed to update inventory: " + e.getMessage());
        }
        return "redirect:/admin/products/" + productId + "/edit";
    }
}
