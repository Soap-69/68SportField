package com.cardshowcase.service;

import com.cardshowcase.exception.PaymentConcurrencyException;
import com.cardshowcase.model.entity.*;
import com.cardshowcase.payment.GatewayOutcome;
import com.cardshowcase.payment.PaymentConfirmationResult;
import com.cardshowcase.payment.PaymentResult;
import com.cardshowcase.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceUnitTest {

    @Mock PaymentRepository paymentRepository;
    @Mock PaymentEventRepository paymentEventRepository;
    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock InventoryRepository inventoryRepository;
    @Mock InventoryAllocationRepository inventoryAllocationRepository;
    @Mock OrderService orderService;
    @Mock EntityManager em;
    @InjectMocks PaymentService paymentService;

    // ── Shared fixtures ───────────────────────────────────────────────

    private Order order;
    private Payment pendingPayment;
    private OrderItem orderItem;
    private ProductVariant variant;
    private Inventory inventory;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(paymentService, "em", em);
        Category cat = Category.builder().id(1L).name("Cat").isActive(true).build();
        Product product = Product.builder().id(1L).name("Test Card").isActive(true).category(cat).build();
        variant = ProductVariant.builder().id(1L).product(product).variantType("Box")
                .price(new BigDecimal("50.00")).isActive(true).build();

        order = Order.builder().id(1L).orderNumber("ORD-TEST-001")
                .status(OrderStatus.PENDING_PAYMENT).total(new BigDecimal("100.00"))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        pendingPayment = Payment.builder().id(1L).order(order)
                .provider("MANUAL").status(PaymentStatus.PENDING)
                .amount(new BigDecimal("100.00")).currency("USD")
                .idempotencyKey("key-001")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        InventoryLocation location = InventoryLocation.builder().id(1L).name("Main").isActive(true).build();
        inventory = Inventory.builder().id(1L).variant(variant).location(location)
                .quantity(10).build();

        orderItem = OrderItem.builder().id(1L).order(order).productVariant(variant)
                .productName("Test Card").variantTypeSnapshot("Box").skuSnapshot("SKU-001")
                .quantity(2).unitPrice(new BigDecimal("50.00"))
                .lineSubtotal(new BigDecimal("100.00")).build();
    }

    // ── 1. Happy path: PENDING → SUCCEEDED → Order PAID ──────────────

    @Test
    void confirmPayment_successOutcome_transitionsToSucceeded_andOrderToPaid() {
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(pendingPayment));
        when(orderItemRepository.findByOrder_IdOrderByIdAsc(1L)).thenReturn(List.of(orderItem));
        when(inventoryRepository.findActiveByVariantIdOrderByLocationIdAsc(1L))
                .thenReturn(List.of(inventory));
        when(paymentRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(inventoryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        PaymentResult result = new PaymentResult(GatewayOutcome.SUCCESS, "TX-123", null, null);
        PaymentConfirmationResult outcome = paymentService.confirmSuccessfulPayment(1L, result);

        assertThat(outcome).isInstanceOf(PaymentConfirmationResult.Success.class);
        assertThat(pendingPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(pendingPayment.getProviderPaymentId()).isEqualTo("TX-123");
        verify(orderService).transitionTo(order, OrderStatus.PAID);
        verify(paymentEventRepository, atLeastOnce()).save(argThat(
                e -> e.getEventType() == PaymentEventType.PAYMENT_SUCCEEDED));
    }

    // ── 2. Gateway DECLINED → FAILED (committed, order stays PENDING_PAYMENT) ─

    @Test
    void confirmPayment_declinedOutcome_transitionsToFailed_orderUnchanged() {
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(pendingPayment));
        when(orderItemRepository.findByOrder_IdOrderByIdAsc(1L)).thenReturn(List.of(orderItem));
        when(inventoryRepository.findActiveByVariantIdOrderByLocationIdAsc(1L))
                .thenReturn(List.of(inventory));
        when(paymentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        PaymentResult result = new PaymentResult(GatewayOutcome.DECLINED, null, "DECLINED", "Card declined");
        PaymentConfirmationResult outcome = paymentService.confirmSuccessfulPayment(1L, result);

        assertThat(outcome).isInstanceOf(PaymentConfirmationResult.BusinessFailure.class);
        assertThat(pendingPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(pendingPayment.getFailureCode()).isEqualTo("DECLINED");
        verify(orderService, never()).transitionTo(any(), any());
        verify(paymentEventRepository).save(argThat(
                e -> e.getEventType() == PaymentEventType.PAYMENT_FAILED));
    }

    // ── 3. FAILED → retry (PAYMENT_RETRY_STARTED) → SUCCEEDED ────────

    @Test
    void retryTransition_thenConfirm_succeeds() {
        Payment failedPayment = Payment.builder().id(2L).order(order)
                .provider("MANUAL").status(PaymentStatus.FAILED)
                .failureCode("DECLINED").failureMessage("declined")
                .amount(new BigDecimal("100.00")).currency("USD").idempotencyKey("key-002")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        when(paymentRepository.findById(2L)).thenReturn(Optional.of(failedPayment));
        when(paymentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // Apply retry transition
        Payment retried = paymentService.applyRetryTransition(2L);
        assertThat(retried.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(retried.getFailureCode()).isNull();
        verify(paymentEventRepository).save(argThat(
                e -> e.getEventType() == PaymentEventType.PAYMENT_RETRY_STARTED));

        // Now confirm
        when(orderItemRepository.findByOrder_IdOrderByIdAsc(1L)).thenReturn(List.of(orderItem));
        when(inventoryRepository.findActiveByVariantIdOrderByLocationIdAsc(1L))
                .thenReturn(List.of(inventory));
        when(inventoryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        PaymentResult result = new PaymentResult(GatewayOutcome.SUCCESS, "TX-RETRY", null, null);
        PaymentConfirmationResult outcome = paymentService.confirmSuccessfulPayment(2L, result);

        assertThat(outcome).isInstanceOf(PaymentConfirmationResult.Success.class);
        assertThat(failedPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        verify(orderService).transitionTo(order, OrderStatus.PAID);
    }

    // ── 4. Already SUCCEEDED → idempotent no-op ───────────────────────

    @Test
    void confirmPayment_alreadySucceeded_isIdempotentNoOp() {
        Payment succeededPayment = Payment.builder().id(3L).order(order)
                .provider("MANUAL").status(PaymentStatus.SUCCEEDED)
                .amount(new BigDecimal("100.00")).currency("USD").idempotencyKey("key-003")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        when(paymentRepository.findById(3L)).thenReturn(Optional.of(succeededPayment));

        PaymentResult result = new PaymentResult(GatewayOutcome.SUCCESS, "TX-DUP", null, null);
        PaymentConfirmationResult outcome = paymentService.confirmSuccessfulPayment(3L, result);

        assertThat(outcome).isInstanceOf(PaymentConfirmationResult.Success.class);
        verify(orderService, never()).transitionTo(any(), any());
        verify(inventoryRepository, never()).save(any());
        verify(paymentEventRepository, never()).save(any());
    }

    // ── 5. confirmSuccessfulPayment on FAILED (without retry) → throws ─

    @Test
    void confirmPayment_failedWithoutRetry_throwsIllegalState() {
        Payment failedPayment = Payment.builder().id(4L).order(order)
                .provider("MANUAL").status(PaymentStatus.FAILED)
                .amount(new BigDecimal("100.00")).currency("USD").idempotencyKey("key-004")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        when(paymentRepository.findById(4L)).thenReturn(Optional.of(failedPayment));

        PaymentResult result = new PaymentResult(GatewayOutcome.SUCCESS, null, null, null);
        assertThatThrownBy(() -> paymentService.confirmSuccessfulPayment(4L, result))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("applyRetryTransition");
    }

    // ── 6. OLE thrown at em.flush() (not at save) → PaymentConcurrencyException ─

    @Test
    void confirmPayment_oleThrownAtFlush_throwsPaymentConcurrencyException() {
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(pendingPayment));
        when(orderItemRepository.findByOrder_IdOrderByIdAsc(1L)).thenReturn(List.of(orderItem));
        when(inventoryRepository.findActiveByVariantIdOrderByLocationIdAsc(1L))
                .thenReturn(List.of(inventory));
        when(inventoryRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        // deductInventory save() succeeds, but flush() surfaces the OLE
        doThrow(new ObjectOptimisticLockingFailureException(Inventory.class, 1L))
                .when(em).flush();

        PaymentResult result = new PaymentResult(GatewayOutcome.SUCCESS, "TX-FLUSH", null, null);
        assertThatThrownBy(() -> paymentService.confirmSuccessfulPayment(1L, result))
                .isInstanceOf(PaymentConcurrencyException.class)
                .hasMessageContaining("Concurrent inventory update conflict");

        // Payment must NOT be mutated to SUCCEEDED — OLE rolled back the transaction
        assertThat(pendingPayment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(orderService, never()).transitionTo(any(), any());
    }

    // ── 7. applyRetryTransition: Order not PENDING_PAYMENT → rejected ─

    @Test
    void retryTransition_orderNotPendingPayment_throwsIllegalState_paymentUnmutated() {
        Order cancelledOrder = Order.builder().id(1L).orderNumber("ORD-TEST-001")
                .status(OrderStatus.CANCELLED).total(new BigDecimal("100.00"))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        Payment failedPayment = Payment.builder().id(6L).order(cancelledOrder)
                .provider("MANUAL").status(PaymentStatus.FAILED)
                .failureCode("DECLINED").failureMessage("declined")
                .amount(new BigDecimal("100.00")).currency("USD").idempotencyKey("key-006")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        when(paymentRepository.findById(6L)).thenReturn(Optional.of(failedPayment));

        assertThatThrownBy(() -> paymentService.applyRetryTransition(6L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING_PAYMENT");

        // Payment must not be mutated
        assertThat(failedPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failedPayment.getFailureCode()).isEqualTo("DECLINED");
        verify(paymentRepository, never()).save(any());
        verify(paymentEventRepository, never()).save(any());
    }

    // ── 8. getOrInitializePayment: Order not PENDING_PAYMENT → rejected ─

    @Test
    void initializePayment_orderNotPendingPayment_throwsIllegalState() {
        Order cancelledOrder = Order.builder().id(99L).orderNumber("ORD-TEST-CANCELLED")
                .status(OrderStatus.CANCELLED).total(new BigDecimal("100.00"))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        when(paymentRepository.findByOrder_Id(99L)).thenReturn(Optional.empty());
        when(paymentRepository.findByIdempotencyKey("key-cancel")).thenReturn(Optional.empty());
        when(orderRepository.findById(99L)).thenReturn(Optional.of(cancelledOrder));

        assertThatThrownBy(() -> paymentService.getOrInitializePayment(99L, "MANUAL", "key-cancel"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING_PAYMENT");
    }

    // ── 9. confirmSuccessfulPayment: Order not PENDING_PAYMENT → rejected ─

    @Test
    void confirmPayment_orderNotPendingPayment_throwsIllegalState() {
        Order paidOrder = Order.builder().id(1L).orderNumber("ORD-TEST-001")
                .status(OrderStatus.PAID).total(new BigDecimal("100.00"))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        Payment paymentOnPaidOrder = Payment.builder().id(5L).order(paidOrder)
                .provider("MANUAL").status(PaymentStatus.PENDING)
                .amount(new BigDecimal("100.00")).currency("USD").idempotencyKey("key-005")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

        when(paymentRepository.findById(5L)).thenReturn(Optional.of(paymentOnPaidOrder));
        when(orderItemRepository.findByOrder_IdOrderByIdAsc(1L)).thenReturn(List.of());

        PaymentResult result = new PaymentResult(GatewayOutcome.SUCCESS, null, null, null);
        assertThatThrownBy(() -> paymentService.confirmSuccessfulPayment(5L, result))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING_PAYMENT");

        verify(orderService, never()).transitionTo(any(), any());
        verify(inventoryRepository, never()).save(any());
    }
}
