package com.cardshowcase.payment;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Dev/test-only payment gateway that simulates SUCCESS, DECLINED, or ERROR outcomes.
 *
 * Profile-gated: NOT available in production (requires "dev" or "test" profile).
 * Does NOT call any external service. Not the eventual wire-transfer flow.
 * BOUNDARY: Does not mutate Order state or Inventory — pure I/O adapter.
 */
@Profile({"dev", "test"})
@Component
public class ManualPaymentGateway implements PaymentGateway {

    public static final String PROVIDER = "MANUAL";

    @Override
    public String providerName() { return PROVIDER; }

    @Override
    public PaymentResult processPayment(PaymentRequest request) {
        GatewayOutcome outcome = request.simulatedOutcome() != null
                ? request.simulatedOutcome() : GatewayOutcome.DECLINED;

        return switch (outcome) {
            case SUCCESS -> new PaymentResult(
                    GatewayOutcome.SUCCESS,
                    "MANUAL-" + UUID.randomUUID(),
                    null, null);
            case DECLINED -> new PaymentResult(
                    GatewayOutcome.DECLINED,
                    null, "DECLINED", "Payment was declined by simulated gateway.");
            case ERROR -> new PaymentResult(
                    GatewayOutcome.ERROR,
                    null, "GATEWAY_ERROR", "Simulated gateway error.");
        };
    }

    @Override
    public RefundResult refund(RefundRequest request) {
        return RefundResult.notImplemented();
    }
}
