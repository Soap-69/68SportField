package com.cardshowcase.payment;

import java.math.BigDecimal;

public record RefundRequest(Long paymentId, BigDecimal amount, String reason) {}
