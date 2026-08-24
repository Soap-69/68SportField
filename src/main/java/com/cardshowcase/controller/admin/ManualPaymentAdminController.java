package com.cardshowcase.controller.admin;

import com.cardshowcase.exception.IdempotencyConflictException;
import com.cardshowcase.exception.PaymentConcurrencyException;
import com.cardshowcase.model.dto.ManualConfirmRequest;
import com.cardshowcase.model.dto.PaymentResponse;
import com.cardshowcase.model.entity.PaymentStatus;
import com.cardshowcase.payment.*;
import com.cardshowcase.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Dev/test-only admin endpoint for manually confirming payments.
 * Profile-gated: NOT available in production (requires "dev" or "test" profile).
 *
 * Protected by the existing /admin/** security filter chain:
 *   - Requires ROLE_ADMIN or ROLE_SENIOR_ADMIN.
 *   - CSRF enforced (session-based, same as all admin endpoints).
 *   - No anonymous/public access to mark a payment successful.
 */
@Profile({"dev", "test"})
@Slf4j
@RestController
@RequestMapping("/admin/api/orders/{orderId}/payments")
@RequiredArgsConstructor
public class ManualPaymentAdminController {

    private final PaymentService paymentService;
    private final ManualPaymentGateway manualPaymentGateway;

    /**
     * POST /admin/api/orders/{orderId}/payments/manual-confirm
     * Body: { "outcome": "SUCCESS" | "DECLINED" | "ERROR" }
     *
     * Flow:
     *  1. getOrInitializePayment (creates PENDING Payment if none exists)
     *  2. If FAILED → applyRetryTransition (FAILED → PENDING + event)
     *  3. If already SUCCEEDED → idempotent no-op
     *  4. Call ManualPaymentGateway with simulated outcome
     *  5. confirmSuccessfulPayment (handles all business outcomes + commits)
     */
    @PostMapping("/manual-confirm")
    public ResponseEntity<?> manualConfirm(
            @PathVariable Long orderId,
            @Valid @RequestBody ManualConfirmRequest req) {

        try {
            // 1. Get or create the logical Payment for this order using the caller-supplied key.
            //    Same order + same key → existing Payment (idempotent).
            //    Different order + same key → IdempotencyConflictException → 409.
            var payment = paymentService.getOrInitializePayment(
                    orderId, ManualPaymentGateway.PROVIDER, req.getIdempotencyKey());

            // 2. Already SUCCEEDED → idempotent no-op
            if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
                return ResponseEntity.ok(PaymentResponse.from(payment));
            }

            // 3. If FAILED → explicit retry transition BEFORE confirmation
            if (payment.getStatus() == PaymentStatus.FAILED) {
                payment = paymentService.applyRetryTransition(payment.getId());
            }

            // 4. Call gateway with simulated outcome
            PaymentRequest gatewayReq = new PaymentRequest(
                    orderId, payment.getId(),
                    payment.getAmount(), payment.getCurrency(),
                    payment.getIdempotencyKey(), req.getOutcome());
            PaymentResult gatewayResult = manualPaymentGateway.processPayment(gatewayReq);

            // 5. Apply result (business outcome handled inside, committed by service)
            PaymentConfirmationResult result =
                    paymentService.confirmSuccessfulPayment(payment.getId(), gatewayResult);

            if (result instanceof PaymentConfirmationResult.Success s) {
                return ResponseEntity.ok(PaymentResponse.from(s.payment()));
            } else {
                PaymentConfirmationResult.BusinessFailure f = (PaymentConfirmationResult.BusinessFailure) result;
                return ResponseEntity.unprocessableEntity()
                        .body(Map.of("error", f.reason(), "failureCode", nvl(f.failureCode())));
            }

        } catch (PaymentConcurrencyException e) {
            log.warn("Concurrency conflict during manual payment confirm for order {}: {}",
                    orderId, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Concurrent inventory conflict. Please retry.",
                                 "retryable", true));
        } catch (IdempotencyConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error during manual payment confirmation for order {}", orderId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Payment confirmation failed. Please try again."));
        }
    }

    private static String nvl(String s) { return s != null ? s : ""; }
}
