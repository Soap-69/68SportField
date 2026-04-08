package com.cardshowcase.controller.admin;

import com.cardshowcase.model.entity.AdminUser;
import com.cardshowcase.service.AdminUserService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/settings")
@RequiredArgsConstructor
public class AdminSettingsController {

    private final AdminUserService adminUserService;

    @GetMapping
    public String settingsPage(Model model) {
        AdminUser admin = adminUserService.getCurrentAdmin();
        model.addAttribute("admin",     admin);
        model.addAttribute("pageTitle", "Account Settings");
        return "admin/settings";
    }

    @PostMapping("/update-profile")
    public String updateProfile(@RequestParam String newUsername,
                                RedirectAttributes flash,
                                HttpSession session) {
        AdminUser admin = adminUserService.getCurrentAdmin();
        try {
            adminUserService.updateUsername(admin.getId(), newUsername);
            // Invalidate session so the user must re-authenticate with the new username
            SecurityContextHolder.clearContext();
            session.invalidate();
            // Redirect to login with a message (flash won't survive session invalidation
            // so we use a query param instead)
            return "redirect:/admin/login?usernameChanged";
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/admin/settings";
        }
    }

    @PostMapping("/update-password")
    public String updatePassword(@RequestParam String currentPassword,
                                 @RequestParam String newPassword,
                                 @RequestParam String confirmPassword,
                                 RedirectAttributes flash) {
        AdminUser admin = adminUserService.getCurrentAdmin();
        try {
            adminUserService.updatePassword(admin.getId(), currentPassword, newPassword, confirmPassword);
            flash.addFlashAttribute("successMessage", "Password updated successfully.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/settings";
    }
}
