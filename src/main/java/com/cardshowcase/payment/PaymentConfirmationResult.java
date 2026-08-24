package com.cardshowcase.payment;

import com.cardshowcase.model.entity.Payment;

/**
 * Domain result returned by {@link com.cardshowcase.service.PaymentService#confirmSuccessfulPayment}.
 *
 * Business failures (insufficient stock, gateway DECLINED/ERROR) are represented as
 * {@link BusinessFailure}. The corresponding database state is already committed by the time
 * this result is returned — the failure is NOT signaled by throwing an exception.
 *
 * Concurrency conflicts (optimistic-lock during inventory deduction) are NOT modeled here;
 * they cause a full transaction rollback and are signaled by
 * {@link com.cardshowcase.exception.PaymentConcurrencyException}.
 */
public sealed interface PaymentConfirmationResult
        permits PaymentConfirmationResult.Success, PaymentConfirmationResult.BusinessFailure {

    record Success(Payment payment) implements PaymentConfirmationResult {}

    record BusinessFailure(String reason, String failureCode) implements PaymentConfirmationResult {}

    static PaymentConfirmationResult success(Payment payment) { return new Success(payment); }

    static PaymentConfirmationResult failure(String reason, String failureCode) {
        return new BusinessFailure(reason, failureCode);
    }
}
