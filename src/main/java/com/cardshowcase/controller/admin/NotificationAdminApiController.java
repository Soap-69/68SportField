package com.cardshowcase.controller.admin;

import com.cardshowcase.model.entity.NotificationLog;
import com.cardshowcase.model.entity.NotificationType;
import com.cardshowcase.notification.DeliveryOutcome;
import com.cardshowcase.notification.NotificationDispatcher;
import com.cardshowcase.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Manual resend. ADMIN or SENIOR_ADMIN (enforced by the /admin/** rule), CSRF via header.
 * Never creates a row; goes through the same claim as automatic delivery.
 */
@RestController
@RequestMapping("/admin/api/orders/{orderId}/notifications")
@RequiredArgsConstructor
public class NotificationAdminApiController {

    private final NotificationLogRepository repository;
    private final NotificationDispatcher dispatcher;

    @PostMapping("/{type}/resend")
    public ResponseEntity<?> resend(@PathVariable Long orderId, @PathVariable String type) {
        NotificationType notificationType;
        try {
            notificationType = NotificationType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Unknown notification type: " + type));
        }
        NotificationLog log = repository.findByOrder_IdAndType(orderId, notificationType).orElse(null);
        if (log == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "No " + type + " notification exists for order " + orderId));
        }
        DeliveryOutcome outcome = dispatcher.deliver(log.getId());
        if (outcome == DeliveryOutcome.NOT_CLAIMED) {
            NotificationLog current = repository.findById(log.getId()).orElseThrow();
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Notification is " + current.getStatus() + " and cannot be resent."));
        }
        NotificationLog after = repository.findById(log.getId()).orElseThrow();
        return ResponseEntity.ok(Map.of(
                "type", after.getType().name(),
                "status", after.getStatus().name(),
                "attempts", after.getAttempts(),
                "outcome", outcome.name()));
    }
}
