package com.cardshowcase.controller.admin;

import com.cardshowcase.model.entity.AdminUser;
import com.cardshowcase.model.entity.Order;
import com.cardshowcase.model.entity.RefundRequest;
import com.cardshowcase.repository.AdminUserRepository;
import com.cardshowcase.service.OrderService;
import com.cardshowcase.service.RefundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/admin/api/orders")
@RequiredArgsConstructor
public class AdminOrderApiController {

    private final OrderService orderService;
    private final RefundService refundService;
    private final AdminUserRepository adminUserRepository;

    @PostMapping("/{orderId}/mark-processing")
    public ResponseEntity<?> markProcessing(@PathVariable Long orderId) {
        try {
            Order order = orderService.markProcessing(orderId);
            return ResponseEntity.ok(Map.of("status", order.getStatus().name(), "id", order.getId()));
        } catch (IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{orderId}/dispatch")
    public ResponseEntity<?> dispatch(@PathVariable Long orderId) {
        try {
            Order order = orderService.dispatchShipment(orderId);
            return ResponseEntity.ok(Map.of("status", order.getStatus().name(), "id", order.getId()));
        } catch (IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{orderId}/mark-delivered")
    public ResponseEntity<?> markDelivered(@PathVariable Long orderId) {
        try {
            Order order = orderService.markDelivered(orderId);
            return ResponseEntity.ok(Map.of("status", order.getStatus().name(), "id", order.getId()));
        } catch (IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{orderId}/mark-completed")
    public ResponseEntity<?> markCompleted(@PathVariable Long orderId) {
        try {
            Order order = orderService.markCompleted(orderId);
            return ResponseEntity.ok(Map.of("status", order.getStatus().name(), "id", order.getId()));
        } catch (IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{orderId}/refund-requests")
    public ResponseEntity<?> submitRefundRequest(
            @PathVariable Long orderId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        try {
            AdminUser admin = adminUserRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Admin user not found: " + auth.getName()));

            BigDecimal amount = new BigDecimal(body.get("amount").toString());
            String reason = body.containsKey("reason") ? (String) body.get("reason") : null;

            RefundRequest rr = refundService.submitRefundRequest(orderId, amount, reason, admin.getId());
            return ResponseEntity.ok(Map.of(
                "id", rr.getId(),
                "status", rr.getStatus().name(),
                "requestedAmount", rr.getRequestedAmount()
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
