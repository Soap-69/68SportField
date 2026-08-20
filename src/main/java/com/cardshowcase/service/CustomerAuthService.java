package com.cardshowcase.service;

import com.cardshowcase.model.dto.ProfileUpdateDTO;
import com.cardshowcase.model.dto.RegisterDTO;
import com.cardshowcase.model.entity.Customer;
import com.cardshowcase.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class CustomerAuthService {

    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$");

    private final CustomerRepository customerRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Transactional
    public Customer register(RegisterDTO dto) {
        // Normalize email
        String email = dto.getEmail().trim().toLowerCase();

        if (customerRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalStateException("An account with this email already exists.");
        }

        if (!dto.getPassword().equals(dto.getConfirmPassword())) {
            throw new IllegalArgumentException("Passwords do not match.");
        }

        if (!PASSWORD_PATTERN.matcher(dto.getPassword()).matches()) {
            throw new IllegalArgumentException(
                    "Password must be at least 8 characters and contain at least one uppercase letter, one lowercase letter, and one digit.");
        }

        Customer customer = Customer.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(dto.getPassword()))
                .firstName(dto.getFirstName().trim())
                .lastName(dto.getLastName().trim())
                .phone(dto.getPhone() != null ? dto.getPhone().trim() : null)
                .isActive(true)
                .emailVerified(false)
                .build();

        return customerRepository.save(customer);
    }

    public Customer getCurrentCustomer() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        String email = auth.getName();
        return customerRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("Customer not found: " + email));
    }

    @Transactional
    public Customer updateProfile(Long customerId, ProfileUpdateDTO dto) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + customerId));

        customer.setFirstName(dto.getFirstName().trim());
        customer.setLastName(dto.getLastName().trim());
        customer.setPhone(dto.getPhone() != null ? dto.getPhone().trim() : null);

        return customerRepository.save(customer);
    }

    @Transactional
    public void changePassword(Long customerId, String currentPassword, String newPassword) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + customerId));

        if (!passwordEncoder.matches(currentPassword, customer.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect.");
        }

        if (!PASSWORD_PATTERN.matcher(newPassword).matches()) {
            throw new IllegalArgumentException(
                    "New password must be at least 8 characters and contain at least one uppercase letter, one lowercase letter, and one digit.");
        }

        customer.setPasswordHash(passwordEncoder.encode(newPassword));
        customerRepository.save(customer);
    }
}
