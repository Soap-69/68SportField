package com.cardshowcase.controller.admin;

import com.cardshowcase.model.dto.RecordQuoteRequest;
import com.cardshowcase.model.dto.RecordTrackingRequest;
import com.cardshowcase.model.dto.ShipmentResponse;
import com.cardshowcase.model.entity.Shipment;
import com.cardshowcase.service.ShippingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin API for shipment management: quote entry, payment confirmation, tracking.
 *
 * All endpoints:
 *  - Require ROLE_ADMIN or ROLE_SENIOR_ADMIN (enforced by /admin/** security filter chain)
 *  - Enforce CSRF (session-based, same as all admin endpoints)
 *  - Are production endpoints — NOT profile-gated
 *
 * confirm-payment does NOT create a Payment or PaymentEvent; it only advances
 * Shipment.shippingPaymentStatus from PAYMENT_PENDING to PAID.
 */
@Slf4j
@RestController
@RequestMapping("/admin/api/orders/{orderId}/shipment")
@RequiredArgsConstructor
public class ShippingAdminApiController {

    private final ShippingService shippingService;

    /**
     * POST /admin/api/orders/{orderId}/shipment/quote
     * Records the actual shipping cost: QUOTE_REQUIRED → QUOTED → PAYMENT_PENDING (auto).
     */
    @PostMapping("/quote")
    public ResponseEntity<?> recordQuote(
            @PathVariable Long orderId,
            @Valid @RequestBody RecordQuoteRequest req) {
        try {
            Shipment shipment = shippingService.recordQuote(orderId, req.getAmount());
            return ResponseEntity.ok(ShipmentResponse.from(shipment));
        } catch (IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error recording shipping quote for order {}", orderId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to record shipping quote."));
        }
    }

    /**
     * POST /admin/api/orders/{orderId}/shipment/confirm-payment
     * Confirms supplemental shipping payment received out of band → PAYMENT_PENDING → PAID.
     * Does NOT create a Payment or PaymentEvent.
     */
    @PostMapping("/confirm-payment")
    public ResponseEntity<?> confirmShippingPayment(@PathVariable Long orderId) {
        try {
            Shipment shipment = shippingService.confirmShippingPaymentReceived(orderId);
            return ResponseEntity.ok(ShipmentResponse.from(shipment));
        } catch (IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error confirming shipping payment for order {}", orderId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to confirm shipping payment."));
        }
    }

    /**
     * POST /admin/api/orders/{orderId}/shipment/tracking
     * Records carrier and tracking number. No carrier API validation.
     */
    @PostMapping("/tracking")
    public ResponseEntity<?> recordTracking(
            @PathVariable Long orderId,
            @Valid @RequestBody RecordTrackingRequest req) {
        try {
            Shipment shipment = shippingService.recordTracking(
                    orderId, req.getCarrier(), req.getTrackingNumber());
            return ResponseEntity.ok(ShipmentResponse.from(shipment));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error recording tracking for order {}", orderId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to record tracking information."));
        }
    }
}
