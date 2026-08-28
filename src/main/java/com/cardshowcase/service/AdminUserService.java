package com.cardshowcase.service;

import com.cardshowcase.model.entity.AdminUser;
import com.cardshowcase.model.entity.AdminUserAudit;
import com.cardshowcase.model.entity.AdminUserAuditAction;
import com.cardshowcase.repository.AdminUserAuditRepository;
import com.cardshowcase.repository.AdminUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class AdminUserService {

    private static final Set<String> VALID_ROLES = Set.of("ADMIN", "SENIOR_ADMIN");

    private final AdminUserRepository adminUserRepository;
    private final AdminUserAuditRepository adminUserAuditRepository;
    private final PasswordEncoder passwordEncoder;

    // ── Existing methods ───────────────────────────────────────────────────────

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

    // ── Admin user management ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AdminUser> listAdmins() {
        return adminUserRepository.findAllByOrderByIdAsc();
    }

    /**
     * Creates a new admin user and writes a CREATED audit record.
     *
     * @param username       the new admin's username
     * @param rawPassword    plaintext password — will be encoded via PasswordEncoder
     * @param role           "ADMIN" or "SENIOR_ADMIN"
     * @param actingAdminId  ID of the Senior Admin performing the create
     */
    public AdminUser createAdmin(String username, String rawPassword, String role, Long actingAdminId) {
        if (!VALID_ROLES.contains(role)) {
            throw new IllegalArgumentException("Invalid role: " + role);
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username cannot be blank.");
        }
        if (rawPassword == null || rawPassword.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters.");
        }

        adminUserRepository.findByUsername(username.trim()).ifPresent(u -> {
            throw new IllegalArgumentException("Username \"" + username.trim() + "\" is already taken.");
        });

        AdminUser newAdmin = AdminUser.builder()
                .username(username.trim())
                .password(passwordEncoder.encode(rawPassword))
                .role(role)
                .isActive(true)
                .build();
        newAdmin = adminUserRepository.save(newAdmin);

        AdminUser actor = adminUserRepository.findById(actingAdminId)
                .orElseThrow(() -> new IllegalArgumentException("Acting admin not found: " + actingAdminId));

        AdminUserAudit audit = AdminUserAudit.builder()
                .targetAdminUser(newAdmin)
                .actorAdminUser(actor)
                .action(AdminUserAuditAction.CREATED)
                .oldValue(null)
                .newValue(role)
                .build();
        adminUserAuditRepository.save(audit);

        log.info("Admin [{}] created new admin user [{}] with role [{}]", actor.getUsername(), newAdmin.getUsername(), role);
        return newAdmin;
    }

    /**
     * Changes the role of an existing admin user and writes a ROLE_CHANGED audit record.
     *
     * @throws IllegalArgumentException  if actor tries to change their own role
     * @throws IllegalStateException     if the change would leave zero enabled SENIOR_ADMIN accounts
     */
    public AdminUser changeRole(Long targetAdminId, String newRole, Long actingAdminId) {
        if (!VALID_ROLES.contains(newRole)) {
            throw new IllegalArgumentException("Invalid role: " + newRole);
        }
        if (targetAdminId.equals(actingAdminId)) {
            throw new IllegalArgumentException("Cannot change your own role.");
        }

        AdminUser target = adminUserRepository.findById(targetAdminId)
                .orElseThrow(() -> new IllegalArgumentException("Admin not found: " + targetAdminId));

        // Guard: would this leave zero enabled SENIOR_ADMINs?
        if ("SENIOR_ADMIN".equals(target.getRole()) && !"SENIOR_ADMIN".equals(newRole)) {
            List<AdminUser> locked = adminUserRepository.findAllEnabledSeniorAdminsForUpdate();
            if (locked.size() - 1 <= 0) {
                throw new IllegalStateException("Cannot demote: would leave zero enabled Senior Admin accounts.");
            }
        }

        String oldRole = target.getRole();
        target.setRole(newRole);
        target = adminUserRepository.save(target);

        AdminUser actor = adminUserRepository.findById(actingAdminId)
                .orElseThrow(() -> new IllegalArgumentException("Acting admin not found: " + actingAdminId));

        AdminUserAudit audit = AdminUserAudit.builder()
                .targetAdminUser(target)
                .actorAdminUser(actor)
                .action(AdminUserAuditAction.ROLE_CHANGED)
                .oldValue(oldRole)
                .newValue(newRole)
                .build();
        adminUserAuditRepository.save(audit);

        log.info("Admin [{}] changed role of [{}] from [{}] to [{}]", actor.getUsername(), target.getUsername(), oldRole, newRole);
        return target;
    }

    /**
     * Enables or disables an admin user and writes an ENABLED/DISABLED audit record.
     *
     * @throws IllegalArgumentException  if actor tries to disable their own account
     * @throws IllegalStateException     if disabling would leave zero enabled SENIOR_ADMIN accounts
     */
    public AdminUser setEnabled(Long targetAdminId, boolean enabled, Long actingAdminId) {
        if (!enabled && targetAdminId.equals(actingAdminId)) {
            throw new IllegalArgumentException("Cannot disable your own account.");
        }

        AdminUser target = adminUserRepository.findById(targetAdminId)
                .orElseThrow(() -> new IllegalArgumentException("Admin not found: " + targetAdminId));

        // Guard: would disabling leave zero enabled SENIOR_ADMINs?
        if (!enabled && "SENIOR_ADMIN".equals(target.getRole())) {
            List<AdminUser> locked = adminUserRepository.findAllEnabledSeniorAdminsForUpdate();
            if (locked.size() - 1 <= 0) {
                throw new IllegalStateException("Cannot disable: would leave zero enabled Senior Admin accounts.");
            }
        }

        String oldValue = Boolean.TRUE.equals(target.getIsActive()) ? "true" : "false";
        target.setIsActive(enabled);
        target = adminUserRepository.save(target);

        AdminUser actor = adminUserRepository.findById(actingAdminId)
                .orElseThrow(() -> new IllegalArgumentException("Acting admin not found: " + actingAdminId));

        AdminUserAuditAction action = enabled ? AdminUserAuditAction.ENABLED : AdminUserAuditAction.DISABLED;
        AdminUserAudit audit = AdminUserAudit.builder()
                .targetAdminUser(target)
                .actorAdminUser(actor)
                .action(action)
                .oldValue(oldValue)
                .newValue(String.valueOf(enabled))
                .build();
        adminUserAuditRepository.save(audit);

        log.info("Admin [{}] {} admin [{}]", actor.getUsername(), enabled ? "enabled" : "disabled", target.getUsername());
        return target;
    }
}
