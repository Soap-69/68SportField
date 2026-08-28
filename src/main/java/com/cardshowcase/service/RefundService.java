package com.cardshowcase.service;

import com.cardshowcase.exception.RefundAlreadyProcessedException;
import com.cardshowcase.exception.RefundExecutionException;
import com.cardshowcase.model.entity.*;
import com.cardshowcase.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.cardshowcase.model.entity.OrderStatus.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class RefundService {

    @Lazy
    @Autowired
    private RefundService self;

    private final RefundRequestRepository refundRequestRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryAllocationRepository inventoryAllocationRepository;
    private final AdminUserRepository adminUserRepository;

    /** Creates RefundRequest in PENDING_APPROVAL status */
    @Transactional
    public RefundRequest submitRefundRequest(Long orderId, BigDecimal amount,
                                             String reason, Long requestedByAdminId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        // Must have collected money
        Set<OrderStatus> allowedStatuses = Set.of(PAID, PROCESSING, SHIPPED, DELIVERED, COMPLETED);
        if (!allowedStatuses.contains(order.getStatus())) {
            throw new IllegalStateException("Refund request cannot be submitted for order in status "
                + order.getStatus() + ". Order must be in PAID, PROCESSING, SHIPPED, DELIVERED, or COMPLETED.");
        }

        // No active or executed refund request already
        boolean hasExecuted = refundRequestRepository.findByOrder_IdOrderByCreatedAtAsc(orderId)
            .stream().anyMatch(r -> r.getStatus() == RefundRequestStatus.EXECUTED);
        if (hasExecuted) {
            throw new IllegalStateException("Order " + orderId
                + " already has an executed refund. No further refund requests may be submitted.");
        }

        // amount must equal order total (full refund only)
        if (amount.compareTo(order.getTotal()) != 0) {
            throw new IllegalArgumentException("requested_amount must equal the full order total ("
                + order.getTotal() + ") for full refunds.");
        }

        RefundRequest rr = RefundRequest.builder()
            .order(order)
            .requestedAmount(amount)
            .reason(reason)
            .status(RefundRequestStatus.PENDING_APPROVAL)
            .requestedByAdminId(requestedByAdminId)
            .requestedAt(LocalDateTime.now())
            .build();
        return refundRequestRepository.save(rr);
    }

    /**
     * Orchestrates approval. NOT @Transactional — uses REQUIRES_NEW inner calls.
     * On success: returns EXECUTED RefundRequest.
     * On AlreadyProcessedException: re-throws (409 at controller).
     * On execution failure: commits FAILED status, throws RefundExecutionException.
     */
    public RefundRequest approveRefundRequest(Long id, Long reviewedByAdminId) {
        try {
            return self.executeApprovalInNewTransaction(id, reviewedByAdminId);
        } catch (RefundAlreadyProcessedException e) {
            throw e; // conflict — don't record FAILED
        } catch (Exception e) {
            log.error("Refund execution failed for request {}, committing FAILED status", id, e);
            try {
                self.commitFailedStatus(id, reviewedByAdminId);
            } catch (Exception ex) {
                log.error("Could not commit FAILED status for refund request {}", id, ex);
            }
            throw new RefundExecutionException("Refund execution failed: " + e.getMessage(), e);
        }
    }

    /**
     * REQUIRES_NEW: pessimistic lock, re-check status, execute a+b+c, commit EXECUTED.
     * Throws RefundAlreadyProcessedException if status != PENDING_APPROVAL (concurrent duplicate).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefundRequest executeApprovalInNewTransaction(Long id, Long reviewedByAdminId) {
        RefundRequest rr = refundRequestRepository.findByIdWithLock(id)
            .orElseThrow(() -> new IllegalArgumentException("RefundRequest not found: " + id));

        if (rr.getStatus() != RefundRequestStatus.PENDING_APPROVAL) {
            throw new RefundAlreadyProcessedException(
                "RefundRequest " + id + " is already in status " + rr.getStatus() +
                " — approval rejected.");
        }

        rr.setStatus(RefundRequestStatus.APPROVED);

        // a. Payment → REFUNDED + write PAYMENT_REFUNDED event
        Payment payment = paymentRepository.findByOrder_Id(rr.getOrder().getId())
            .orElseThrow(() -> new IllegalStateException("No payment found for order " + rr.getOrder().getId()));
        if (!payment.getStatus().canTransitionTo(PaymentStatus.REFUNDED)) {
            throw new IllegalStateException("Payment " + payment.getId() + " cannot transition to REFUNDED from " + payment.getStatus());
        }
        payment.setStatus(PaymentStatus.REFUNDED);
        paymentRepository.save(payment);
        writeRefundEvent(payment, rr.getRequestedAmount());

        // b. Order → REFUNDED (capture pre-refund status for inventory decision)
        Order order = orderRepository.findById(rr.getOrder().getId()).orElseThrow();
        OrderStatus preRefundStatus = order.getStatus();
        if (!order.getStatus().canTransitionTo(OrderStatus.REFUNDED)) {
            throw new IllegalStateException("Order " + order.getId() + " cannot transition to REFUNDED from " + order.getStatus());
        }
        order.setStatus(OrderStatus.REFUNDED);
        orderRepository.save(order);

        // c. Inventory restoration: only if goods had not yet physically left the warehouse
        if (!preRefundStatus.isPhysicallyDispatched()) {
            restoreInventory(order.getId());
        }

        rr.setStatus(RefundRequestStatus.EXECUTED);
        rr.setReviewedByAdminId(reviewedByAdminId);
        rr.setReviewedAt(LocalDateTime.now());
        rr.setExecutedAt(LocalDateTime.now());
        return refundRequestRepository.save(rr);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefundRequest commitFailedStatus(Long id, Long reviewedByAdminId) {
        RefundRequest rr = refundRequestRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("RefundRequest not found: " + id));
        rr.setStatus(RefundRequestStatus.FAILED);
        rr.setReviewedByAdminId(reviewedByAdminId);
        rr.setReviewedAt(LocalDateTime.now());
        return refundRequestRepository.save(rr);
    }

    @Transactional
    public RefundRequest rejectRefundRequest(Long id, String rejectionReason, Long reviewedByAdminId) {
        RefundRequest rr = refundRequestRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("RefundRequest not found: " + id));
        if (rr.getStatus() != RefundRequestStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Cannot reject RefundRequest " + id + " in status " + rr.getStatus());
        }
        rr.setStatus(RefundRequestStatus.REJECTED);
        rr.setRejectionReason(rejectionReason);
        rr.setReviewedByAdminId(reviewedByAdminId);
        rr.setReviewedAt(LocalDateTime.now());
        return refundRequestRepository.save(rr);
    }

    @Transactional(readOnly = true)
    public RefundRequest findById(Long id) {
        return refundRequestRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("RefundRequest not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<RefundRequest> findByOrderId(Long orderId) {
        return refundRequestRepository.findByOrder_IdOrderByCreatedAtAsc(orderId);
    }

    private void restoreInventory(Long orderId) {
        List<InventoryAllocation> allocations = inventoryAllocationRepository.findByOrderId(orderId);
        if (allocations.isEmpty()) {
            log.warn("No allocation records found for order {} — stock not restored", orderId);
            return;
        }
        for (InventoryAllocation alloc : allocations) {
            Inventory inv = alloc.getInventory();
            inv.setQuantity(inv.getQuantity() + alloc.getQuantityCommitted());
            inventoryRepository.save(inv);
            log.debug("Restored {} units to inventory {} for order {}",
                alloc.getQuantityCommitted(), inv.getId(), orderId);
        }
    }

    private void writeRefundEvent(Payment payment, BigDecimal amount) {
        PaymentEvent event = PaymentEvent.builder()
            .payment(payment)
            .eventId(UUID.randomUUID().toString())
            .eventType(PaymentEventType.PAYMENT_REFUNDED)
            .provider(payment.getProvider())
            .metadata("{\"amount\":\"" + amount + "\",\"type\":\"FULL_REFUND\"}")
            .build();
        paymentEventRepository.save(event);
    }
}
