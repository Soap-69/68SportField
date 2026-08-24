package com.cardshowcase.service;

import com.cardshowcase.exception.IdempotencyConflictException;
import com.cardshowcase.exception.PaymentConcurrencyException;
import com.cardshowcase.model.entity.*;
import com.cardshowcase.payment.GatewayOutcome;
import com.cardshowcase.payment.PaymentConfirmationResult;
import com.cardshowcase.payment.PaymentResult;
import com.cardshowcase.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final InventoryRepository inventoryRepository;
    private final OrderService orderService;

    @PersistenceContext
    private EntityManager em;

    // ── Initialization ────────────────────────────────────────────────

    /**
     * Returns the existing Payment for this order if one already exists, otherwise
     * creates a new PENDING Payment and writes a PAYMENT_CREATED event.
     *
     * Idempotency rules (freeze — do not alter):
     * - Same order + existing Payment → return existing, regardless of idempotency key.
     * - Same idempotency key + different order → reject (IdempotencyConflictException).
     */
    @Transactional
    public Payment getOrInitializePayment(Long orderId, String provider, String idempotencyKey) {
        // Return existing payment for this order (regardless of provided key)
        var existing = paymentRepository.findByOrder_Id(orderId);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Reject if the idempotency key is already used for a DIFFERENT order
        paymentRepository.findByIdempotencyKey(idempotencyKey).ifPresent(p -> {
            if (!p.getOrder().getId().equals(orderId)) {
                throw new IdempotencyConflictException(
                        "Idempotency key '" + idempotencyKey +
                        "' is already in use for a different order.");
            }
        });

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException(
                    "Cannot initialize payment for order " + orderId + " in status " +
                    order.getStatus() + ". Order must be PENDING_PAYMENT.");
        }

        Payment payment = Payment.builder()
                .order(order)
                .provider(provider)
                .status(PaymentStatus.PENDING)
                .amount(order.getTotal())
                .currency("USD")
                .idempotencyKey(idempotencyKey)
                .build();
        payment = paymentRepository.save(payment);
        writeEvent(payment, PaymentEventType.PAYMENT_CREATED, null);

        log.info("Payment {} initialized for order {} (provider={})",
                payment.getId(), order.getOrderNumber(), provider);
        return payment;
    }

    // ── Retry transition ──────────────────────────────────────────────

    /**
     * Explicitly transitions a FAILED payment back to PENDING and records a
     * PAYMENT_RETRY_STARTED event. Must be called BEFORE re-calling
     * {@link #confirmSuccessfulPayment} — confirmSuccessfulPayment operates only on PENDING.
     */
    @Transactional
    public Payment applyRetryTransition(Long paymentId) {
        Payment payment = loadPayment(paymentId);
        if (!payment.getStatus().canTransitionTo(PaymentStatus.PENDING)) {
            throw new IllegalStateException(
                    "Payment " + paymentId + " cannot be retried from status " +
                    payment.getStatus() + ". Only FAILED payments can be retried.");
        }
        if (payment.getOrder().getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException(
                    "Payment " + paymentId + " cannot be retried: order " +
                    payment.getOrder().getId() + " is " + payment.getOrder().getStatus() +
                    ". Retry is only allowed while Order=PENDING_PAYMENT.");
        }
        payment.setStatus(PaymentStatus.PENDING);
        payment.setFailureCode(null);
        payment.setFailureMessage(null);
        payment = paymentRepository.save(payment);
        writeEvent(payment, PaymentEventType.PAYMENT_RETRY_STARTED, null);
        log.info("Payment {} retry started: FAILED → PENDING", paymentId);
        return payment;
    }

    // ── Confirmation ──────────────────────────────────────────────────

    /**
     * Processes the gateway result for a PENDING payment.
     *
     * <p>Business failures (insufficient stock, gateway DECLINED/ERROR) result in a COMMITTED
     * FAILED state — NOT a transaction rollback. The failure is returned as a
     * {@link PaymentConfirmationResult.BusinessFailure}; no exception is thrown.
     *
     * <p>Confirming an already-SUCCEEDED payment is an idempotent no-op (returns success,
     * no new event, no double-deduction).
     *
     * <p>Optimistic-lock conflict during inventory deduction causes a full transaction rollback
     * (Payment stays PENDING) and throws {@link PaymentConcurrencyException}. This is NOT a
     * business failure.
     *
     * @throws IllegalStateException if payment is not PENDING (and not SUCCEEDED)
     * @throws PaymentConcurrencyException if concurrent inventory update conflict — retryable
     */
    @Transactional
    public PaymentConfirmationResult confirmSuccessfulPayment(Long paymentId, PaymentResult gatewayResult) {
        Payment payment = loadPayment(paymentId);

        // Idempotent no-op: already confirmed
        if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
            log.info("Payment {} already SUCCEEDED — idempotent no-op", paymentId);
            return PaymentConfirmationResult.success(payment);
        }

        // Must be PENDING — caller must call applyRetryTransition first for FAILED payments
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new IllegalStateException(
                    "confirmSuccessfulPayment requires PENDING status, but payment " + paymentId +
                    " is " + payment.getStatus() +
                    ". Call applyRetryTransition first for FAILED payments.");
        }

        Order order = payment.getOrder();
        List<OrderItem> items = orderItemRepository.findByOrder_IdOrderByIdAsc(order.getId());

        // Guard: Order must be PENDING_PAYMENT for any new confirmation work
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException(
                    "Cannot confirm payment for order " + order.getId() + " in status " +
                    order.getStatus() + ". Order must be PENDING_PAYMENT.");
        }

        // 1. Revalidate all items: active status + stock (authoritative gate, same as checkout)
        for (OrderItem item : items) {
            ProductVariant v = item.getProductVariant();
            if (!Boolean.TRUE.equals(v.getIsActive())) {
                return commitFailure(payment, "ITEM_UNAVAILABLE",
                        "\"" + v.getProduct().getName() + " (" + v.getVariantType() +
                        ")\" is no longer available.");
            }
            if (!Boolean.TRUE.equals(v.getProduct().getIsActive())) {
                return commitFailure(payment, "ITEM_UNAVAILABLE",
                        "\"" + v.getProduct().getName() + "\" is no longer available.");
            }
            int available = sumActiveStock(v.getId());
            if (item.getQuantity() > available) {
                return commitFailure(payment, "INSUFFICIENT_STOCK",
                        String.format("Insufficient stock for \"%s (%s)\": requested %d, available %d",
                                v.getProduct().getName(), v.getVariantType(),
                                item.getQuantity(), available));
            }
        }

        // 2. Check gateway result
        if (gatewayResult.outcome() != GatewayOutcome.SUCCESS) {
            String code = gatewayResult.failureCode() != null
                    ? gatewayResult.failureCode() : gatewayResult.outcome().name();
            String msg  = gatewayResult.failureMessage() != null
                    ? gatewayResult.failureMessage() : "Payment " + gatewayResult.outcome().name().toLowerCase();
            return commitFailure(payment, code, msg);
        }

        // 3. Deduct inventory deterministically — optimistic lock may propagate here
        try {
            deductInventory(items);
            // Force Hibernate to flush deferred UPDATEs now so any OLE is detected
            // inside this catch block, before Payment is mutated or Order is transitioned.
            em.flush();
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
            // NOT a business failure — entire transaction will roll back.
            // Payment stays in its pre-confirmation state (PENDING). Caller retries.
            throw new PaymentConcurrencyException(
                    "Concurrent inventory update conflict for payment " + paymentId +
                    ". Retry the confirmation.", e);
        }

        // 4. Mark payment SUCCEEDED
        payment.setStatus(PaymentStatus.SUCCEEDED);
        payment.setProviderPaymentId(gatewayResult.providerPaymentId());
        paymentRepository.save(payment);
        writeEvent(payment, PaymentEventType.PAYMENT_SUCCEEDED,
                "{\"providerPaymentId\":\"" + nvl(gatewayResult.providerPaymentId()) + "\"}");

        // 5. Transition order to PAID
        orderService.transitionTo(order, OrderStatus.PAID);

        log.info("Payment {} SUCCEEDED for order {} (provider={}, providerPaymentId={})",
                paymentId, order.getOrderNumber(), payment.getProvider(),
                gatewayResult.providerPaymentId());
        return PaymentConfirmationResult.success(payment);
    }

    // ── Queries ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Payment findById(Long paymentId) { return loadPayment(paymentId); }

    @Transactional(readOnly = true)
    public java.util.Optional<Payment> findByOrderId(Long orderId) {
        return paymentRepository.findByOrder_Id(orderId);
    }

    // ── Private helpers ───────────────────────────────────────────────

    /**
     * Marks payment FAILED and writes PAYMENT_FAILED event within the current transaction.
     * Returns a BusinessFailure result — does NOT throw, so Spring commits this failure state.
     */
    private PaymentConfirmationResult commitFailure(Payment payment, String failureCode, String reason) {
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureCode(failureCode);
        payment.setFailureMessage(reason);
        paymentRepository.save(payment);
        writeEvent(payment, PaymentEventType.PAYMENT_FAILED,
                "{\"failureCode\":\"" + nvl(failureCode) + "\",\"reason\":\"" + nvl(reason) + "\"}");
        log.info("Payment {} FAILED: [{}] {}", payment.getId(), failureCode, reason);
        return PaymentConfirmationResult.failure(reason, failureCode);
    }

    /**
     * Deducts inventory for all order items within the caller's single transaction.
     *
     * Algorithm: load active inventory rows for each variant ordered deterministically
     * by location ID ASC; deduct sequentially until the requested quantity is satisfied.
     *
     * After exhausting all rows, asserts {@code remaining == 0}. If stock disappeared
     * between the pre-deduction validation and the deduction itself (race window), the
     * remaining quantity will be positive. In that case, throwing
     * {@link PaymentConcurrencyException} causes the entire transaction to roll back —
     * no partial deduction is ever committed.
     *
     * May also throw ObjectOptimisticLockingFailureException — caller wraps it as
     * {@link PaymentConcurrencyException} to trigger the same rollback path.
     */
    private void deductInventory(List<OrderItem> items) {
        for (OrderItem item : items) {
            int remaining = item.getQuantity();
            List<Inventory> rows = inventoryRepository
                    .findActiveByVariantIdOrderByLocationIdAsc(item.getProductVariant().getId());
            for (Inventory inv : rows) {
                if (remaining <= 0) break;
                int deduct = Math.min(remaining, inv.getQuantity());
                inv.setQuantity(inv.getQuantity() - deduct);
                inventoryRepository.save(inv); // may throw OLE → propagates to caller
                remaining -= deduct;
            }
            if (remaining > 0) {
                // Stock was consumed between validation and deduction (race window).
                // Throw to roll back the entire transaction — no partial deduction committed.
                throw new PaymentConcurrencyException(
                        "Inventory consistency failure for variant " +
                        item.getProductVariant().getId() +
                        ": stock disappeared during deduction (remaining=" + remaining + "). " +
                        "Retry the confirmation.", null);
            }
        }
    }

    private int sumActiveStock(Long variantId) {
        return inventoryRepository.findActiveByVariantIdOrderByLocationIdAsc(variantId)
                .stream().mapToInt(Inventory::getQuantity).sum();
    }

    private Payment loadPayment(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));
    }

    private void writeEvent(Payment payment, PaymentEventType type, String metadata) {
        PaymentEvent event = PaymentEvent.builder()
                .payment(payment)
                .eventId(UUID.randomUUID().toString())
                .eventType(type)
                .provider(payment.getProvider())
                .metadata(metadata)
                .build();
        paymentEventRepository.save(event);
    }

    private static String nvl(String s) { return s != null ? s : ""; }
}
