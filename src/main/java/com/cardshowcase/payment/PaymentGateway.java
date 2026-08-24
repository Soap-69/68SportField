package com.cardshowcase.payment;

/**
 * Provider-agnostic payment gateway contract.
 *
 * Implementations may call external payment providers and return a {@link PaymentResult}.
 * BOUNDARY: Gateways are pure I/O adapters — they MUST NOT directly mutate Order state
 * or Inventory. All business-side effects live exclusively in PaymentService.
 *
 * Refund support is declared in the contract but execution is out of scope for Week 5.
 */
public interface PaymentGateway {
    String providerName();
    PaymentResult processPayment(PaymentRequest request);
    RefundResult refund(RefundRequest request);
}
