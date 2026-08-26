package com.cardshowcase.model.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public class RecordQuoteRequest {

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.00", message = "amount must be >= 0.00")
    private BigDecimal amount;

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
