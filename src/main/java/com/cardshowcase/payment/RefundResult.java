package com.cardshowcase.payment;

public record RefundResult(boolean success, String providerRefundId, String message) {
    public static RefundResult notImplemented() {
        return new RefundResult(false, null, "Refund not implemented for this provider.");
    }
}
