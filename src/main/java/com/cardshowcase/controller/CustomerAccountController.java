package com.cardshowcase.controller;

import com.cardshowcase.model.dto.AddressDTO;
import com.cardshowcase.model.dto.ProfileUpdateDTO;
import com.cardshowcase.model.entity.Customer;
import com.cardshowcase.model.entity.CustomerAddress;
import com.cardshowcase.service.CustomerAddressService;
import com.cardshowcase.service.CustomerAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/account")
@RequiredArgsConstructor
public class CustomerAccountController {

    private final CustomerAuthService customerAuthService;
    private final CustomerAddressService addressService;

    // ── Dashboard ────────────────────────────────────────────────

    @GetMapping
    public String dashboard() {
        return "redirect:/account/profile";
    }

    // ── Profile ──────────────────────────────────────────────────

    @GetMapping("/profile")
    public String profilePage(Model model) {
        Customer customer = customerAuthService.getCurrentCustomer();
        model.addAttribute("customer", customer);
        model.addAttribute("profileDTO", toProfileDTO(customer));
        return "account/profile";
    }

    @PostMapping("/profile")
    public String updateProfile(@Valid ProfileUpdateDTO profileDTO,
                                BindingResult bindingResult,
                                RedirectAttributes redirectAttributes,
                                Model model) {
        Customer customer = customerAuthService.getCurrentCustomer();
        if (bindingResult.hasErrors()) {
            model.addAttribute("customer", customer);
            return "account/profile";
        }
        customerAuthService.updateProfile(customer.getId(), profileDTO);
        redirectAttributes.addFlashAttribute("successMessage", "Profile updated successfully.");
        return "redirect:/account/profile";
    }

    // ── Addresses ────────────────────────────────────────────────

    @GetMapping("/addresses")
    public String addressesPage(Model model) {
        Customer customer = customerAuthService.getCurrentCustomer();
        List<CustomerAddress> addresses = addressService.getAddressesByCustomer(customer.getId());
        model.addAttribute("addresses", addresses);
        return "account/addresses";
    }

    @GetMapping("/addresses/add")
    public String addAddressPage(Model model) {
        model.addAttribute("addressDTO", new AddressDTO());
        model.addAttribute("isEdit", false);
        return "account/address-form";
    }

    @PostMapping("/addresses/add")
    public String addAddress(@Valid AddressDTO addressDTO,
                             BindingResult bindingResult,
                             RedirectAttributes redirectAttributes,
                             Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("isEdit", false);
            return "account/address-form";
        }
        Customer customer = customerAuthService.getCurrentCustomer();
        addressService.createAddress(customer.getId(), addressDTO);
        redirectAttributes.addFlashAttribute("successMessage", "Address saved successfully.");
        return "redirect:/account/addresses";
    }

    @GetMapping("/addresses/{id}/edit")
    public String editAddressPage(@PathVariable Long id, Model model) {
        Customer customer = customerAuthService.getCurrentCustomer();
        CustomerAddress address = addressService.getAddressById(id, customer.getId());
        model.addAttribute("addressDTO", toAddressDTO(address));
        model.addAttribute("addressId", id);
        model.addAttribute("isEdit", true);
        return "account/address-form";
    }

    @PostMapping("/addresses/{id}/edit")
    public String editAddress(@PathVariable Long id,
                              @Valid AddressDTO addressDTO,
                              BindingResult bindingResult,
                              RedirectAttributes redirectAttributes,
                              Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("addressId", id);
            model.addAttribute("isEdit", true);
            return "account/address-form";
        }
        Customer customer = customerAuthService.getCurrentCustomer();
        addressService.updateAddress(id, customer.getId(), addressDTO);
        redirectAttributes.addFlashAttribute("successMessage", "Address updated successfully.");
        return "redirect:/account/addresses";
    }

    @PostMapping("/addresses/{id}/delete")
    public String deleteAddress(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        Customer customer = customerAuthService.getCurrentCustomer();
        addressService.deleteAddress(id, customer.getId());
        redirectAttributes.addFlashAttribute("successMessage", "Address deleted successfully.");
        return "redirect:/account/addresses";
    }

    @PostMapping("/addresses/{id}/set-default-billing")
    public String setDefaultBilling(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        Customer customer = customerAuthService.getCurrentCustomer();
        addressService.setDefaultBilling(id, customer.getId());
        redirectAttributes.addFlashAttribute("successMessage", "Default billing address updated.");
        return "redirect:/account/addresses";
    }

    @PostMapping("/addresses/{id}/set-default-shipping")
    public String setDefaultShipping(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        Customer customer = customerAuthService.getCurrentCustomer();
        addressService.setDefaultShipping(id, customer.getId());
        redirectAttributes.addFlashAttribute("successMessage", "Default shipping address updated.");
        return "redirect:/account/addresses";
    }

    // ── Change password ───────────────────────────────────────────

    @GetMapping("/change-password")
    public String changePasswordPage() {
        return "account/change-password";
    }

    @PostMapping("/change-password")
    public String changePassword(@RequestParam String currentPassword,
                                 @RequestParam String newPassword,
                                 @RequestParam String confirmNewPassword,
                                 RedirectAttributes redirectAttributes,
                                 Model model) {
        if (!newPassword.equals(confirmNewPassword)) {
            model.addAttribute("errorMessage", "New passwords do not match.");
            return "account/change-password";
        }
        try {
            Customer customer = customerAuthService.getCurrentCustomer();
            customerAuthService.changePassword(customer.getId(), currentPassword, newPassword);
            redirectAttributes.addFlashAttribute("successMessage", "Password changed successfully.");
            return "redirect:/account/change-password";
        } catch (IllegalArgumentException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return "account/change-password";
        }
    }

    // ── Orders placeholder ────────────────────────────────────────

    @GetMapping("/orders")
    public String ordersPage() {
        return "account/orders";
    }

    // ── Helpers ───────────────────────────────────────────────────

    private ProfileUpdateDTO toProfileDTO(Customer c) {
        var dto = new ProfileUpdateDTO();
        dto.setFirstName(c.getFirstName());
        dto.setLastName(c.getLastName());
        dto.setPhone(c.getPhone());
        return dto;
    }

    private AddressDTO toAddressDTO(CustomerAddress a) {
        var dto = new AddressDTO();
        dto.setLabel(a.getLabel());
        dto.setFirstName(a.getFirstName());
        dto.setLastName(a.getLastName());
        dto.setCompany(a.getCompany());
        dto.setAddressLine1(a.getAddressLine1());
        dto.setAddressLine2(a.getAddressLine2());
        dto.setCity(a.getCity());
        dto.setState(a.getState());
        dto.setZipCode(a.getZipCode());
        dto.setCountry(a.getCountry());
        dto.setPhone(a.getPhone());
        return dto;
    }
}
