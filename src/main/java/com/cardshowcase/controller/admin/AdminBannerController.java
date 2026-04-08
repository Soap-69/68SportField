package com.cardshowcase.controller.admin;

import com.cardshowcase.model.dto.BannerDTO;
import com.cardshowcase.model.entity.Banner;
import com.cardshowcase.service.BannerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/admin/banners")
@RequiredArgsConstructor
public class AdminBannerController {

    private final BannerService bannerService;

    // ── List ──────────────────────────────────────────────────────────────────

    @GetMapping
    public String list(Model model) {
        List<Banner> banners = bannerService.getAllBanners();
        model.addAttribute("pageTitle", "Banner Management");
        model.addAttribute("banners", banners);
        return "admin/banners/list";
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @GetMapping("/create")
    public String createForm(Model model) {
        model.addAttribute("pageTitle", "Add Banner");
        model.addAttribute("form", new BannerDTO());
        model.addAttribute("editMode", false);
        return "admin/banners/form";
    }

    @PostMapping("/create")
    public String create(@Valid @ModelAttribute("form") BannerDTO form,
                         BindingResult binding,
                         @RequestParam(value = "imageFile", required = false) MultipartFile imageFile,
                         Model model,
                         RedirectAttributes flash) {
        if (binding.hasErrors()) {
            model.addAttribute("pageTitle", "Add Banner");
            model.addAttribute("editMode", false);
            return "admin/banners/form";
        }
        try {
            bannerService.createBanner(form, imageFile);
            flash.addFlashAttribute("successMessage", "Banner created successfully.");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/banners";
    }

    // ── Edit ──────────────────────────────────────────────────────────────────

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Banner banner = bannerService.getBannerById(id);
        BannerDTO form = bannerService.toDTO(id);
        model.addAttribute("pageTitle", "Edit Banner");
        model.addAttribute("form", form);
        model.addAttribute("banner", banner);
        model.addAttribute("editMode", true);
        return "admin/banners/form";
    }

    @PostMapping("/{id}/edit")
    public String edit(@PathVariable Long id,
                       @Valid @ModelAttribute("form") BannerDTO form,
                       BindingResult binding,
                       @RequestParam(value = "imageFile", required = false) MultipartFile imageFile,
                       Model model,
                       RedirectAttributes flash) {
        if (binding.hasErrors()) {
            Banner banner = bannerService.getBannerById(id);
            model.addAttribute("pageTitle", "Edit Banner");
            model.addAttribute("banner", banner);
            model.addAttribute("editMode", true);
            return "admin/banners/form";
        }
        try {
            bannerService.updateBanner(id, form, imageFile);
            flash.addFlashAttribute("successMessage", "Banner updated successfully.");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/admin/banners/" + id + "/edit";
        }
        return "redirect:/admin/banners";
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        try {
            bannerService.deleteBanner(id);
            flash.addFlashAttribute("successMessage", "Banner deleted successfully.");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/banners";
    }

    // ── Toggle Active ─────────────────────────────────────────────────────────

    @PostMapping("/{id}/toggle-active")
    public String toggleActive(@PathVariable Long id, RedirectAttributes flash) {
        try {
            bannerService.toggleActive(id);
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/banners";
    }
}
