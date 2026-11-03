package com.cardshowcase.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Tax boundary. No tax rate has been approved, so every order is taxed at 0.00.
 * This method is the single place a future rule will be applied; nothing else
 * computes tax.
 */
@Service
public class TaxService {

    private static final BigDecimal NO_TAX = new BigDecimal("0.00");

    public BigDecimal calculateTax(BigDecimal subtotal) {
        return NO_TAX;
    }
}
