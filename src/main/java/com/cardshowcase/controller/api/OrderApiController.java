package com.cardshowcase.controller.api;

import com.cardshowcase.model.dto.OrderResponse;
import com.cardshowcase.model.entity.Order;
import com.cardshowcase.repository.OrderItemRepository;
import com.cardshowcase.service.CustomerPrincipal;
import com.cardshowcase.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderApiController {

    private final OrderService orderService;
    private final OrderItemRepository orderItemRepository;

    /**
     * GET /api/orders/{id}
     *
     * Returns the order only if it belongs to the authenticated customer.
     * Guests get no equivalent lookup endpoint (per Week 4 scope).
     * Security config restricts this path to ROLE_CUSTOMER; the service
     * enforces ownership (403 if the order belongs to a different customer).
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getOrder(@PathVariable Long id,
                                      @AuthenticationPrincipal CustomerPrincipal principal) {
        try {
            Order order = orderService.findByIdForCustomer(id, principal.getId());
            var items = orderItemRepository.findByOrder_IdOrderByIdAsc(order.getId());
            return ResponseEntity.ok(OrderResponse.from(order, items));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Access denied."));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
