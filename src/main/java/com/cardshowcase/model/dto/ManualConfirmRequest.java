package com.cardshowcase.model.dto;

import com.cardshowcase.payment.GatewayOutcome;
import jakarta.validation.constraints.NotNull;

public class ManualConfirmRequest {
    @NotNull(message = "outcome is required (SUCCESS, DECLINED, or ERROR)")
    private GatewayOutcome outcome;

    public GatewayOutcome getOutcome() { return outcome; }
    public void setOutcome(GatewayOutcome outcome) { this.outcome = outcome; }
}
