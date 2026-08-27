package com.cardshowcase;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for GET /api/shipping/quote.
 *
 * Verifies that the endpoint is publicly accessible (no auth required),
 * delegates to ShippingService.calculateQuote(), and returns the correct
 * JSON shape for every branch of the shipping rules:
 *   - Ground >= $500  → RESOLVED, amount=0
 *   - Ground <  $500  → REQUIRES_MANUAL_QUOTE, amount=null
 *   - Next Day Air    → RESOLVED, amount=150 (surcharge, regardless of subtotal)
 *   - AK              → AK_HI_DEFERRED, amount=null
 *   - HI              → AK_HI_DEFERRED, amount=null
 */
class ShippingQuoteApiTest extends BaseIntegrationTest {

    // ── Ground, continental US ────────────────────────────────────────

    @Test
    void ground_atThreshold_resolvedFree() throws Exception {
        mockMvc.perform(get("/api/shipping/quote")
                        .param("state", "NY")
                        .param("subtotal", "500.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.amount").value(0));
    }

    @Test
    void ground_aboveThreshold_resolvedFree() throws Exception {
        mockMvc.perform(get("/api/shipping/quote")
                        .param("state", "TX")
                        .param("subtotal", "999.99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.amount").value(0));
    }

    @Test
    void ground_belowThreshold_requiresManualQuote() throws Exception {
        mockMvc.perform(get("/api/shipping/quote")
                        .param("state", "CA")
                        .param("subtotal", "499.99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REQUIRES_MANUAL_QUOTE"))
                .andExpect(jsonPath("$.amount").doesNotExist());
    }

    // ── Next Day Air ──────────────────────────────────────────────────

    @Test
    void nextDayAir_aboveThreshold_resolvedWithSurcharge() throws Exception {
        mockMvc.perform(get("/api/shipping/quote")
                        .param("state", "FL")
                        .param("subtotal", "600.00")
                        .param("serviceLevel", "NEXT_DAY_AIR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.amount").value(150.0));
    }

    @Test
    void nextDayAir_belowThreshold_resolvedWithSurcharge() throws Exception {
        // NDA surcharge applies regardless of subtotal — never REQUIRES_MANUAL_QUOTE
        mockMvc.perform(get("/api/shipping/quote")
                        .param("state", "OH")
                        .param("subtotal", "50.00")
                        .param("serviceLevel", "NEXT_DAY_AIR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.amount").value(150.0));
    }

    // ── AK / HI deferred ─────────────────────────────────────────────

    @Test
    void alaska_akHiDeferred() throws Exception {
        mockMvc.perform(get("/api/shipping/quote")
                        .param("state", "AK")
                        .param("subtotal", "50.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AK_HI_DEFERRED"))
                .andExpect(jsonPath("$.amount").doesNotExist());
    }

    @Test
    void hawaii_akHiDeferred() throws Exception {
        mockMvc.perform(get("/api/shipping/quote")
                        .param("state", "HI")
                        .param("subtotal", "999.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AK_HI_DEFERRED"))
                .andExpect(jsonPath("$.amount").doesNotExist());
    }

    // ── Access control ────────────────────────────────────────────────

    @Test
    void endpoint_isPublic_noAuthRequired() throws Exception {
        // Unauthenticated callers must get 200, not 401/403/redirect
        mockMvc.perform(get("/api/shipping/quote")
                        .param("state", "MA")
                        .param("subtotal", "250.00"))
                .andExpect(status().isOk());
    }

    // ── Input validation ──────────────────────────────────────────────

    @Test
    void blankState_returns400() throws Exception {
        mockMvc.perform(get("/api/shipping/quote")
                        .param("state", "")
                        .param("subtotal", "100.00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void whitespaceOnlyState_returns400() throws Exception {
        mockMvc.perform(get("/api/shipping/quote")
                        .param("state", "   ")
                        .param("subtotal", "100.00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void negativeSubtotal_returns400() throws Exception {
        mockMvc.perform(get("/api/shipping/quote")
                        .param("state", "NY")
                        .param("subtotal", "-0.01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidState_returns400() throws Exception {
        mockMvc.perform(get("/api/shipping/quote")
                        .param("state", "ZZ")
                        .param("subtotal", "100.00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void zeroSubtotal_isValid() throws Exception {
        // $0 subtotal is a valid edge case (empty cart cleared after add — shouldn't happen
        // in practice but must not 400)
        mockMvc.perform(get("/api/shipping/quote")
                        .param("state", "CA")
                        .param("subtotal", "0.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REQUIRES_MANUAL_QUOTE"));
    }
}
