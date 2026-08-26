package com.cardshowcase.controller.api;

import com.cardshowcase.model.entity.ServiceLevel;
import com.cardshowcase.service.ShippingService;
import com.cardshowcase.shipping.ShippingQuote;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Public, read-only endpoint for the shipping rules engine.
 * Called by the checkout page JS to preview the shipping cost before submission.
 * No auth required; no CSRF token needed (GET is idempotent and state-changing is excluded).
 */
@RestController
@RequestMapping("/api/shipping/quote")
@RequiredArgsConstructor
public class ShippingQuoteApiController {

    private final ShippingService shippingService;

    /**
     * GET /api/shipping/quote?state=TX&subtotal=499.99[&serviceLevel=GROUND]
     *
     * Delegates entirely to ShippingService.calculateQuote — the single source of truth
     * for all shipping rules. Returns the ShippingQuote as JSON:
     *   {"status":"RESOLVED","amount":0.00}
     *   {"status":"REQUIRES_MANUAL_QUOTE","amount":null}
     *   {"status":"AK_HI_DEFERRED","amount":null}
     */
    @GetMapping
    public ShippingQuote getShippingQuote(
            @RequestParam String state,
            @RequestParam BigDecimal subtotal,
            @RequestParam(defaultValue = "GROUND") ServiceLevel serviceLevel) {

        return shippingService.calculateQuote(state, serviceLevel, subtotal);
    }
}
