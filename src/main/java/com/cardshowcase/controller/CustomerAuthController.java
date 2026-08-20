package com.cardshowcase.controller;

import com.cardshowcase.model.dto.RegisterDTO;
import com.cardshowcase.model.entity.Customer;
import com.cardshowcase.service.CustomerAuthService;
import com.cardshowcase.service.CustomerPrincipal;
import com.cardshowcase.service.CustomerUserDetailsService;
import com.cardshowcase.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class CustomerAuthController {

    private final CustomerAuthService customerAuthService;
    private final CustomerUserDetailsService customerUserDetailsService;
    private final PasswordResetService passwordResetService;

    // ── Login ────────────────────────────────────────────────────

    @GetMapping("/login")
    public String loginPage(@RequestParam(required = false) String error, Model model) {
        if (error != null) {
            model.addAttribute("errorMessage", "Invalid email or password.");
        }
        return "login";
    }

    // POST /login is handled by Spring Security's form login filter

    // ── Register ─────────────────────────────────────────────────

    @GetMapping("/register")
    public String registerPage(Model model) {
        model.addAttribute("registerDTO", new RegisterDTO());
        return "register";
    }

    @PostMapping("/register")
    public String register(@Valid RegisterDTO registerDTO,
                           BindingResult bindingResult,
                           HttpServletRequest request,
                           RedirectAttributes redirectAttributes,
                           Model model) {
        if (bindingResult.hasErrors()) {
            return "register";
        }

        try {
            Customer customer = customerAuthService.register(registerDTO);

            // Auto-login: set authentication directly in the security context
            UserDetails userDetails = customerUserDetailsService.loadUserByUsername(customer.getEmail());
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            HttpSession session = request.getSession(true);
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

            redirectAttributes.addFlashAttribute("successMessage", "Account created successfully! Welcome!");
            return "redirect:/account";

        } catch (IllegalStateException e) {
            // Duplicate email
            bindingResult.rejectValue("email", "email.duplicate", e.getMessage());
            return "register";
        } catch (IllegalArgumentException e) {
            // Password mismatch or complexity
            if (e.getMessage().contains("match")) {
                bindingResult.rejectValue("confirmPassword", "password.mismatch", e.getMessage());
            } else {
                bindingResult.rejectValue("password", "password.invalid", e.getMessage());
            }
            return "register";
        }
    }

    // ── Forgot password ──────────────────────────────────────────

    @GetMapping("/forgot-password")
    public String forgotPasswordPage() {
        return "forgot-password";
    }

    @PostMapping("/forgot-password")
    public String forgotPassword(@RequestParam String email,
                                 HttpServletRequest request,
                                 RedirectAttributes redirectAttributes) {
        String baseUrl = request.getScheme() + "://" + request.getServerName()
                + (request.getServerPort() != 80 && request.getServerPort() != 443
                   ? ":" + request.getServerPort() : "");
        passwordResetService.requestPasswordReset(email.trim().toLowerCase(), baseUrl);

        redirectAttributes.addFlashAttribute("successMessage",
                "If an account exists with this email, we've sent a reset link. Please check your inbox.");
        return "redirect:/forgot-password";
    }

    // ── Reset password ───────────────────────────────────────────

    @GetMapping("/reset-password")
    public String resetPasswordPage(@RequestParam String token, Model model) {
        model.addAttribute("token", token);
        return "reset-password";
    }

    @PostMapping("/reset-password")
    public String resetPassword(@RequestParam String token,
                                @RequestParam String newPassword,
                                @RequestParam String confirmPassword,
                                RedirectAttributes redirectAttributes,
                                Model model) {
        if (!newPassword.equals(confirmPassword)) {
            model.addAttribute("token", token);
            model.addAttribute("errorMessage", "Passwords do not match.");
            return "reset-password";
        }

        try {
            passwordResetService.resetPassword(token, newPassword);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Password reset successfully. Please sign in with your new password.");
            return "redirect:/login";
        } catch (IllegalArgumentException e) {
            model.addAttribute("token", token);
            model.addAttribute("errorMessage", e.getMessage());
            return "reset-password";
        }
    }
}
