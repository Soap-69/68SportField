package com.cardshowcase.service;

import com.cardshowcase.model.entity.ServiceLevel;
import com.cardshowcase.model.entity.ShippingPaymentStatus;
import com.cardshowcase.repository.ShipmentRepository;
import com.cardshowcase.shipping.ShippingQuote;
import com.cardshowcase.shipping.ShippingQuoteStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ShippingServiceUnitTest {

    @Mock ShipmentRepository shipmentRepository;

    ShippingService shippingService;

    @BeforeEach
    void setUp() {
        shippingService = new ShippingService(shipmentRepository);
    }

    // ── calculateQuote: continental US rules ─────────────────────────

    @Test
    void calculateQuote_ground_atThreshold_resolved_free() {
        ShippingQuote q = shippingService.calculateQuote("NY", ServiceLevel.GROUND, new BigDecimal("500.00"));
        assertThat(q.status()).isEqualTo(ShippingQuoteStatus.RESOLVED);
        assertThat(q.amount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void calculateQuote_ground_aboveThreshold_resolved_free() {
        ShippingQuote q = shippingService.calculateQuote("TX", ServiceLevel.GROUND, new BigDecimal("999.99"));
        assertThat(q.status()).isEqualTo(ShippingQuoteStatus.RESOLVED);
        assertThat(q.amount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void calculateQuote_ground_belowThreshold_requiresManualQuote() {
        ShippingQuote q = shippingService.calculateQuote("CA", ServiceLevel.GROUND, new BigDecimal("499.99"));
        assertThat(q.status()).isEqualTo(ShippingQuoteStatus.REQUIRES_MANUAL_QUOTE);
        assertThat(q.amount()).isNull();
    }

    @Test
    void calculateQuote_nextDayAir_aboveThreshold_resolved_surcharge() {
        ShippingQuote q = shippingService.calculateQuote("FL", ServiceLevel.NEXT_DAY_AIR, new BigDecimal("600.00"));
        assertThat(q.status()).isEqualTo(ShippingQuoteStatus.RESOLVED);
        assertThat(q.amount()).isEqualByComparingTo(ShippingService.NEXT_DAY_AIR_SURCHARGE);
    }

    @Test
    void calculateQuote_nextDayAir_belowThreshold_resolved_surcharge_regardlessOfSubtotal() {
        // Next Day Air surcharge applies regardless of subtotal — do NOT return REQUIRES_MANUAL_QUOTE
        ShippingQuote q = shippingService.calculateQuote("OH", ServiceLevel.NEXT_DAY_AIR, new BigDecimal("50.00"));
        assertThat(q.status()).isEqualTo(ShippingQuoteStatus.RESOLVED);
        assertThat(q.amount()).isEqualByComparingTo(ShippingService.NEXT_DAY_AIR_SURCHARGE);
    }

    // ── calculateQuote: AK/HI bypass ─────────────────────────────────

    @Test
    void calculateQuote_alaska_akHiDeferred() {
        ShippingQuote q = shippingService.calculateQuote("AK", ServiceLevel.GROUND, new BigDecimal("50.00"));
        assertThat(q.status()).isEqualTo(ShippingQuoteStatus.AK_HI_DEFERRED);
        assertThat(q.amount()).isNull();
    }

    @Test
    void calculateQuote_hawaii_akHiDeferred() {
        ShippingQuote q = shippingService.calculateQuote("HI", ServiceLevel.NEXT_DAY_AIR, new BigDecimal("999.00"));
        assertThat(q.status()).isEqualTo(ShippingQuoteStatus.AK_HI_DEFERRED);
        assertThat(q.amount()).isNull();
    }

    @Test
    void calculateQuote_akLowercase_akHiDeferred() {
        // State matching must be case-insensitive
        ShippingQuote q = shippingService.calculateQuote("ak", ServiceLevel.GROUND, new BigDecimal("200.00"));
        assertThat(q.status()).isEqualTo(ShippingQuoteStatus.AK_HI_DEFERRED);
    }

    // ── ShippingPaymentStatus state machine ───────────────────────────

    @Test
    void status_quoteRequired_canTransitionTo_quoted() {
        assertThat(ShippingPaymentStatus.QUOTE_REQUIRED.canTransitionTo(ShippingPaymentStatus.QUOTED)).isTrue();
    }

    @Test
    void status_quoted_canTransitionTo_paymentPending() {
        assertThat(ShippingPaymentStatus.QUOTED.canTransitionTo(ShippingPaymentStatus.PAYMENT_PENDING)).isTrue();
    }

    @Test
    void status_paymentPending_canTransitionTo_paid() {
        assertThat(ShippingPaymentStatus.PAYMENT_PENDING.canTransitionTo(ShippingPaymentStatus.PAID)).isTrue();
    }

    @Test
    void status_quoted_canTransitionTo_waived() {
        assertThat(ShippingPaymentStatus.QUOTED.canTransitionTo(ShippingPaymentStatus.WAIVED)).isTrue();
    }

    @Test
    void status_paymentPending_canTransitionTo_waived() {
        assertThat(ShippingPaymentStatus.PAYMENT_PENDING.canTransitionTo(ShippingPaymentStatus.WAIVED)).isTrue();
    }

    @Test
    void status_notRequired_isTerminal() {
        for (ShippingPaymentStatus next : ShippingPaymentStatus.values()) {
            assertThat(ShippingPaymentStatus.NOT_REQUIRED.canTransitionTo(next))
                    .as("NOT_REQUIRED → " + next + " should be rejected")
                    .isFalse();
        }
    }

    @Test
    void status_paid_isTerminal() {
        for (ShippingPaymentStatus next : ShippingPaymentStatus.values()) {
            assertThat(ShippingPaymentStatus.PAID.canTransitionTo(next))
                    .as("PAID → " + next + " should be rejected")
                    .isFalse();
        }
    }

    @Test
    void status_waived_isTerminal() {
        for (ShippingPaymentStatus next : ShippingPaymentStatus.values()) {
            assertThat(ShippingPaymentStatus.WAIVED.canTransitionTo(next))
                    .as("WAIVED → " + next + " should be rejected")
                    .isFalse();
        }
    }

    @Test
    void status_quoteRequired_cannotSkipToPaymentPending() {
        // Must go through QUOTED first
        assertThat(ShippingPaymentStatus.QUOTE_REQUIRED.canTransitionTo(ShippingPaymentStatus.PAYMENT_PENDING))
                .isFalse();
    }

    @Test
    void status_quoteRequired_cannotGoToPaid_directly() {
        assertThat(ShippingPaymentStatus.QUOTE_REQUIRED.canTransitionTo(ShippingPaymentStatus.PAID))
                .isFalse();
    }
}
