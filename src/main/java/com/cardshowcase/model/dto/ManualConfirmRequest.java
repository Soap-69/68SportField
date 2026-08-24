package com.cardshowcase.model.dto;

import com.cardshowcase.payment.GatewayOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class ManualConfirmRequest {

    @NotNull(message = "outcome is required (SUCCESS, DECLINED, or ERROR)")
    private GatewayOutcome outcome;

    /**
     * Caller-supplied idempotency key for this payment attempt.
     * Same order + same key → returns the same logical Payment (idempotent).
     * Different order + same key → 409 conflict.
     */
    @NotBlank(message = "idempotencyKey is required")
    private String idempotencyKey;

    public GatewayOutcome getOutcome() { return outcome; }
    public void setOutcome(GatewayOutcome outcome) { this.outcome = outcome; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
