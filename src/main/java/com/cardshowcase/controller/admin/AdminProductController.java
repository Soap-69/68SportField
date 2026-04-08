package com.cardshowcase.controller.admin;

import com.cardshowcase.model.dto.ProductDTO;
import com.cardshowcase.model.entity.Category;
import com.cardshowcase.model.entity.Product;
import com.cardshowcase.service.CategoryService;
import com.cardshowcase.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.propertyeditors.CustomNumberEditor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin/products")
@RequiredArgsConstructor
public class AdminProductController {

    private final ProductService  productService;
    private final CategoryService categoryService;

    /** Allow empty string → null for Long fields (e.g. categoryId when nothing selected). */
    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(Long.class, new CustomNumberEditor(Long.class, true));
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @GetMapping
    public String list(@RequestParam(required = false) String search,
                       @RequestParam(required = false) Long categoryId,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0")  int page,
                       @RequestParam(defaultValue = "20") int size,
                       Model model) {

        Pageable pageable = PageRequest.of(page, size,
                Sort.by("sortOrder").ascending().and(Sort.by("name").ascending()));

        Page<Product> products = productService.getProductList(search, categoryId, status, pageable);

        List<Long> ids = products.getContent().stream().map(Product::getId).toList();
        Map<Long, String> primaryImages = productService.getPrimaryImageUrls(ids);

        model.addAttribute("pageTitle",     "Product Management");
        model.addAttribute("products",      products);
        model.addAttribute("primaryImages", primaryImages);
        model.addAttribute("l3Options",     categoryService.getL3WithPath());
        model.addAttribute("search",        search);
        model.addAttribute("categoryId",    categoryId);
        model.addAttribute("status",        status);
        return "admin/products/list";
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @GetMapping("/create")
    public String createForm(Model model) {
        model.addAttribute("pageTitle",    "Add Product");
        model.addAttribute("form",         new ProductDTO());
        model.addAttribute("l1Categories", categoryService.getL1Categories());
        model.addAttribute("l2Categories", Collections.emptyList());
        model.addAttribute("l3Categories", Collections.emptyList());
        model.addAttribute("selectedL1",   null);
        model.addAttribute("selectedL2",   null);
        model.addAttribute("editMode",     false);
        return "admin/products/form";
    }

    @PostMapping("/create")
    public String create(@Valid @ModelAttribute("form") ProductDTO form,
                         BindingResult binding,
                         @RequestParam(value = "imageFiles", required = false) List<MultipartFile> images,
                         Model model,
                         RedirectAttributes flash) {
        if (binding.hasErrors()) {
            populateFormModel(model, "Add Product", null, form.getCategoryId(), false);
            return "admin/products/form";
        }
        try {
            productService.createProduct(form, images);
            flash.addFlashAttribute("successMessage",
                    "Product \"" + form.getName() + "\" created successfully.");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/products";
    }

    // ── Edit ──────────────────────────────────────────────────────────────────

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Product product = productService.getProductByIdWithImages(id);
        ProductDTO form = productService.toDTO(id);

        // Derive L1 / L2 from the product's L3 category (eagerly loaded)
        Category l3 = product.getCategory();
        Category l2 = l3  != null ? l3.getParent()  : null;
        Category l1 = l2  != null ? l2.getParent()  : null;
        Long l1Id   = l1  != null ? l1.getId()       : null;
        Long l2Id   = l2  != null ? l2.getId()       : null;

        model.addAttribute("pageTitle",    "Edit Product");
        model.addAttribute("form",         form);
        model.addAttribute("product",      product);
        model.addAttribute("l1Categories", categoryService.getL1Categories());
        model.addAttribute("l2Categories", categoryService.getChildrenOf(l1Id));
        model.addAttribute("l3Categories", categoryService.getChildrenOf(l2Id));
        model.addAttribute("selectedL1",   l1Id);
        model.addAttribute("selectedL2",   l2Id);
        model.addAttribute("editMode",     true);
        return "admin/products/form";
    }

    @PostMapping("/{id}/edit")
    public String edit(@PathVariable Long id,
                       @Valid @ModelAttribute("form") ProductDTO form,
                       BindingResult binding,
                       @RequestParam(value = "imageFiles",    required = false) List<MultipartFile> newImages,
                       @RequestParam(value = "deleteImageIds", required = false) List<Long> deleteImageIds,
                       Model model,
                       RedirectAttributes flash) {
        if (binding.hasErrors()) {
            Product product = productService.getProductByIdWithImages(id);
            populateFormModel(model, "Edit Product", product, form.getCategoryId(), true);
            return "admin/products/form";
        }
        try {
            productService.updateProduct(id, form, newImages, deleteImageIds);
            flash.addFlashAttribute("successMessage", "Product updated successfully.");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/admin/products/" + id + "/edit";
        }
        return "redirect:/admin/products";
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        try {
            productService.deleteProduct(id);
            flash.addFlashAttribute("successMessage", "Product deleted successfully.");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/products";
    }

    // ── Toggle active ─────────────────────────────────────────────────────────

    @PostMapping("/{id}/toggle-active")
    public String toggleActive(@PathVariable Long id,
                               @RequestParam(defaultValue = "/admin/products") String returnTo,
                               RedirectAttributes flash) {
        try {
            productService.toggleActive(id);
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:" + returnTo;
    }

    // ── Toggle flag ───────────────────────────────────────────────────────────

    @PostMapping("/{id}/toggle-flag")
    public String toggleFlag(@PathVariable Long id,
                             @RequestParam String flag,
                             @RequestParam(defaultValue = "/admin/products") String returnTo,
                             RedirectAttributes flash) {
        try {
            productService.toggleFlag(id, flag);
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:" + returnTo;
    }

    // ── Set primary image ─────────────────────────────────────────────────────

    @PostMapping("/{id}/set-primary-image")
    public String setPrimaryImage(@PathVariable Long id,
                                  @RequestParam Long imageId,
                                  RedirectAttributes flash) {
        productService.setPrimaryImage(id, imageId);
        flash.addFlashAttribute("successMessage", "Primary image updated.");
        return "redirect:/admin/products/" + id + "/edit";
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Re-populates model attributes needed to re-render the form after a binding error.
     */
    private void populateFormModel(Model model, String title,
                                   Product product, Long categoryId, boolean editMode) {
        Category l3 = null, l2 = null, l1 = null;
        if (product != null) {
            l3 = product.getCategory();
            l2 = l3 != null ? l3.getParent()  : null;
            l1 = l2 != null ? l2.getParent()  : null;
        } else if (categoryId != null) {
            // best-effort: derive from the submitted categoryId
            try {
                l3 = categoryService.findById(categoryId);
                l2 = l3 != null ? l3.getParent() : null;
                l1 = l2 != null ? l2.getParent() : null;
            } catch (Exception ignored) {}
        }
        Long l1Id = l1 != null ? l1.getId() : null;
        Long l2Id = l2 != null ? l2.getId() : null;

        model.addAttribute("pageTitle",    title);
        model.addAttribute("product",      product);
        model.addAttribute("l1Categories", categoryService.getL1Categories());
        model.addAttribute("l2Categories", categoryService.getChildrenOf(l1Id));
        model.addAttribute("l3Categories", categoryService.getChildrenOf(l2Id));
        model.addAttribute("selectedL1",   l1Id);
        model.addAttribute("selectedL2",   l2Id);
        model.addAttribute("editMode",     editMode);
    }
}
