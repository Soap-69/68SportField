package com.cardshowcase.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TaxServiceTest {

    @Test
    void calculateTax_returnsZeroWithTwoDecimals_forAnySubtotal() {
        TaxService tax = new TaxService();
        for (String subtotal : new String[]{"0.00", "49.99", "600.00", "12345.67"}) {
            BigDecimal result = tax.calculateTax(new BigDecimal(subtotal));
            assertThat(result).isEqualByComparingTo("0.00");
            assertThat(result.scale()).isEqualTo(2);
            assertThat(result.toPlainString()).isEqualTo("0.00");
        }
    }
}
