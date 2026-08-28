package com.cardshowcase.controller.admin;

import com.cardshowcase.exception.RefundAlreadyProcessedException;
import com.cardshowcase.exception.RefundExecutionException;
import com.cardshowcase.model.entity.AdminUser;
import com.cardshowcase.model.entity.RefundRequest;
import com.cardshowcase.repository.AdminUserRepository;
import com.cardshowcase.service.RefundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/admin/api/refund-requests")
@RequiredArgsConstructor
public class RefundAdminApiController {

    private final RefundService refundService;
    private final AdminUserRepository adminUserRepository;

    /**
     * POST /admin/api/refund-requests/{id}/approve
     * SENIOR_ADMIN only — enforced by SecurityConfig.
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable Long id, Authentication auth) {
        try {
            AdminUser admin = adminUserRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Admin user not found: " + auth.getName()));
            RefundRequest rr = refundService.approveRefundRequest(id, admin.getId());
            return ResponseEntity.ok(Map.of(
                "id", rr.getId(),
                "status", rr.getStatus().name()
            ));
        } catch (RefundAlreadyProcessedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (RefundExecutionException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /admin/api/refund-requests/{id}/reject
     * SENIOR_ADMIN only — enforced by SecurityConfig.
     */
    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            Authentication auth) {
        try {
            AdminUser admin = adminUserRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Admin user not found: " + auth.getName()));
            String rejectionReason = body != null ? body.get("rejectionReason") : null;
            RefundRequest rr = refundService.rejectRefundRequest(id, rejectionReason, admin.getId());
            return ResponseEntity.ok(Map.of(
                "id", rr.getId(),
                "status", rr.getStatus().name()
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
