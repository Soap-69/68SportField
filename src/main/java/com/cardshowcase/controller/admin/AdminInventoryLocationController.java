package com.cardshowcase.controller.admin;

import com.cardshowcase.model.entity.InventoryLocation;
import com.cardshowcase.repository.InventoryLocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/inventory")
@RequiredArgsConstructor
public class AdminInventoryLocationController {

    private final InventoryLocationRepository locationRepo;

    @GetMapping("/locations")
    public String list(Model model) {
        model.addAttribute("pageTitle", "Inventory Locations");
        model.addAttribute("locations", locationRepo.findAll());
        return "admin/inventory/locations";
    }

    @PostMapping("/locations/create")
    public String create(@RequestParam String name,
                         @RequestParam(required = false) String address,
                         RedirectAttributes flash) {
        try {
            InventoryLocation loc = InventoryLocation.builder()
                    .name(name.trim())
                    .address(address != null ? address.trim() : null)
                    .build();
            locationRepo.save(loc);
            flash.addFlashAttribute("successMessage", "Location \"" + name + "\" created.");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/inventory/locations";
    }

    @PostMapping("/locations/{id}/edit")
    public String edit(@PathVariable Long id,
                       @RequestParam String name,
                       @RequestParam(required = false) String address,
                       RedirectAttributes flash) {
        try {
            InventoryLocation loc = locationRepo.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Location not found: " + id));
            loc.setName(name.trim());
            loc.setAddress(address != null ? address.trim() : null);
            locationRepo.save(loc);
            flash.addFlashAttribute("successMessage", "Location updated.");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/inventory/locations";
    }

    @PostMapping("/locations/{id}/toggle-active")
    public String toggleActive(@PathVariable Long id, RedirectAttributes flash) {
        try {
            InventoryLocation loc = locationRepo.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Location not found: " + id));
            loc.setIsActive(!Boolean.TRUE.equals(loc.getIsActive()));
            locationRepo.save(loc);
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/inventory/locations";
    }
}
