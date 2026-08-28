package com.cardshowcase.controller.admin;

import com.cardshowcase.model.entity.AdminUser;
import com.cardshowcase.repository.AdminUserRepository;
import com.cardshowcase.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for admin user management actions — consumed by JavaScript fetch() in list.html.
 * All endpoints require ROLE_SENIOR_ADMIN (enforced in SecurityConfig).
 */
@RestController
@RequestMapping("/admin/api/users")
@RequiredArgsConstructor
public class AdminUserApiController {

    private final AdminUserService adminUserService;
    private final AdminUserRepository adminUserRepository;

    /** POST /admin/api/users — create a new admin user */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody Map<String, String> body,
            Authentication auth) {
        try {
            Long actingAdminId = resolveActingAdminId(auth);
            AdminUser created = adminUserService.createAdmin(
                    body.get("username"),
                    body.get("password"),
                    body.get("role"),
                    actingAdminId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "id", created.getId(),
                    "username", created.getUsername(),
                    "role", created.getRole()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** POST /admin/api/users/{id}/role — change an admin's role */
    @PostMapping("/{id}/role")
    public ResponseEntity<Map<String, Object>> changeRole(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        try {
            Long actingAdminId = resolveActingAdminId(auth);
            AdminUser updated = adminUserService.changeRole(id, body.get("role"), actingAdminId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "id", updated.getId(),
                    "role", updated.getRole()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** POST /admin/api/users/{id}/enabled — enable or disable an admin account */
    @PostMapping("/{id}/enabled")
    public ResponseEntity<Map<String, Object>> setEnabled(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        try {
            Long actingAdminId = resolveActingAdminId(auth);
            boolean enabled = Boolean.parseBoolean(String.valueOf(body.get("enabled")));
            AdminUser updated = adminUserService.setEnabled(id, enabled, actingAdminId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "id", updated.getId(),
                    "isActive", updated.getIsActive()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    private Long resolveActingAdminId(Authentication auth) {
        return adminUserRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated admin not found"))
                .getId();
    }
}
