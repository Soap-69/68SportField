package com.cardshowcase.controller.admin;

import com.cardshowcase.model.dto.CategoryForm;
import com.cardshowcase.service.CategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.propertyeditors.CustomNumberEditor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/categories")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final CategoryService categoryService;

    /** Allow empty string → null binding for the optional Long parentId field. */
    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(Long.class, new CustomNumberEditor(Long.class, true));
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @GetMapping
    public String list(Model model) {
        model.addAttribute("pageTitle",  "Category Management");
        model.addAttribute("categories", categoryService.getCategoryTreeFlat());
        return "admin/categories/list";
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @GetMapping("/create")
    public String createForm(Model model) {
        model.addAttribute("pageTitle",     "Add Category");
        model.addAttribute("form",          new CategoryForm());
        model.addAttribute("parentOptions", categoryService.getParentOptions());
        model.addAttribute("editMode",      false);
        return "admin/categories/form";
    }

    @PostMapping("/create")
    public String create(@Valid @ModelAttribute("form") CategoryForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes flash) {
        if (binding.hasErrors()) {
            model.addAttribute("pageTitle",     "Add Category");
            model.addAttribute("parentOptions", categoryService.getParentOptions());
            model.addAttribute("editMode",      false);
            return "admin/categories/form";
        }
        try {
            categoryService.createCategory(form);
            flash.addFlashAttribute("successMessage", "Category \"" + form.getName() + "\" created successfully.");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/categories";
    }

    // ── Edit ──────────────────────────────────────────────────────────────────

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("pageTitle",     "Edit Category");
        model.addAttribute("form",          categoryService.toForm(id));
        model.addAttribute("parentOptions", categoryService.getParentOptionsExcluding(id));
        model.addAttribute("editMode",      true);
        return "admin/categories/form";
    }

    @PostMapping("/{id}/edit")
    public String edit(@PathVariable Long id,
                       @Valid @ModelAttribute("form") CategoryForm form,
                       BindingResult binding,
                       Model model,
                       RedirectAttributes flash) {
        if (binding.hasErrors()) {
            model.addAttribute("pageTitle",     "Edit Category");
            model.addAttribute("parentOptions", categoryService.getParentOptionsExcluding(id));
            model.addAttribute("editMode",      true);
            return "admin/categories/form";
        }
        try {
            categoryService.updateCategory(id, form);
            flash.addFlashAttribute("successMessage", "Category \"" + form.getName() + "\" updated successfully.");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/categories";
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        try {
            categoryService.deleteCategory(id);
            flash.addFlashAttribute("successMessage", "Category deleted successfully.");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/categories";
    }

    // ── Toggle Active ─────────────────────────────────────────────────────────

    @PostMapping("/{id}/toggle-active")
    public String toggleActive(@PathVariable Long id, RedirectAttributes flash) {
        try {
            categoryService.toggleActive(id);
            flash.addFlashAttribute("successMessage", "Category status updated.");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/categories";
    }
}
