package com.cardshowcase.controller.api;

import com.cardshowcase.exception.IdempotencyConflictException;
import com.cardshowcase.exception.InsufficientStockException;
import com.cardshowcase.model.dto.CheckoutRequest;
import com.cardshowcase.model.dto.OrderResponse;
import com.cardshowcase.model.entity.Order;
import com.cardshowcase.repository.OrderItemRepository;
import com.cardshowcase.service.CustomerPrincipal;
import com.cardshowcase.service.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/checkout")
@RequiredArgsConstructor
public class CheckoutApiController {

    private final OrderService orderService;
    private final OrderItemRepository orderItemRepository;

    /**
     * POST /api/checkout
     *
     * Creates an order from the caller's resolved cart (guest cookie or authenticated
     * customer cart). The cart is NEVER identified by a client-supplied cart_id — any
     * such field in the JSON body is silently ignored.
     *
     * CSRF protection: enforced (same pattern as /api/cart/**). Guests hold a session
     * token materialised by the CSRF filter; that session carries the CSRF token used
     * to validate this request.
     *
     * DataIntegrityViolationException on the idempotency_key UNIQUE constraint means a
     * near-simultaneous duplicate raced us to the DB. The losing thread looks up the
     * winner's order and applies the same identity check before returning it.
     */
    @PostMapping
    public ResponseEntity<?> checkout(
            @Valid @RequestBody CheckoutRequest req,
            @AuthenticationPrincipal CustomerPrincipal principal,
            HttpServletRequest httpReq,
            HttpServletResponse httpResp) {

        try {
            Order order = orderService.checkout(req, httpReq, httpResp, principal);
            var items = orderItemRepository.findByOrder_IdOrderByIdAsc(order.getId());
            return ResponseEntity.ok(OrderResponse.from(order, items));

        } catch (IdempotencyConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));

        } catch (InsufficientStockException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("error", e.getMessage()));

        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));

        } catch (DataIntegrityViolationException e) {
            // Near-simultaneous duplicate: the UNIQUE constraint on idempotency_key fired.
            // Resolve the order created by the winning thread (with identity check).
            log.warn("Concurrent idempotency key conflict for key={}; resolving winner",
                    req.getIdempotencyKey());
            return orderService.findForIdempotentReplay(req.getIdempotencyKey(), httpReq, principal)
                    .<ResponseEntity<?>>map(order -> {
                        var items = orderItemRepository.findByOrder_IdOrderByIdAsc(order.getId());
                        return ResponseEntity.ok(OrderResponse.from(order, items));
                    })
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(Map.of("error", "Concurrent checkout conflict. Please retry.")));

        } catch (Exception e) {
            log.error("Unexpected error during checkout", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Checkout failed. Please try again."));
        }
    }
}
