package com.cardshowcase.service;

import com.cardshowcase.model.entity.AdminUser;
import com.cardshowcase.repository.AdminUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class AdminUserService {

    private final AdminUserRepository adminUserRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    /** Loads the currently authenticated admin from the DB. */
    @Transactional(readOnly = true)
    public AdminUser getCurrentAdmin() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return adminUserRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Authenticated admin not found: " + username));
    }

    /**
     * Updates the username for the given admin.
     * Throws {@link IllegalArgumentException} if the new username is already taken.
     */
    public void updateUsername(Long id, String newUsername) {
        String trimmed = newUsername == null ? "" : newUsername.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Username cannot be blank.");
        }
        adminUserRepository.findByUsername(trimmed).ifPresent(existing -> {
            if (!existing.getId().equals(id)) {
                throw new IllegalArgumentException("Username \"" + trimmed + "\" is already taken.");
            }
        });
        AdminUser admin = adminUserRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Admin not found: " + id));
        admin.setUsername(trimmed);
        adminUserRepository.save(admin);
    }

    /**
     * Updates the password for the given admin.
     * Validates current password, length, confirmation match, and that it differs from the old one.
     */
    public void updatePassword(Long id, String currentPassword, String newPassword, String confirmPassword) {
        AdminUser admin = adminUserRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Admin not found: " + id));

        if (!passwordEncoder.matches(currentPassword, admin.getPassword())) {
            throw new IllegalArgumentException("Current password is incorrect.");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("New password must be at least 8 characters.");
        }
        if (!newPassword.equals(confirmPassword)) {
            throw new IllegalArgumentException("New password and confirmation do not match.");
        }
        if (passwordEncoder.matches(newPassword, admin.getPassword())) {
            throw new IllegalArgumentException("New password must be different from the current password.");
        }

        admin.setPassword(passwordEncoder.encode(newPassword));
        adminUserRepository.save(admin);
    }
}
