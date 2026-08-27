package com.cardshowcase.service;

import com.cardshowcase.model.entity.*;
import com.cardshowcase.repository.ShipmentRepository;
import com.cardshowcase.shipping.ShippingQuote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * Shipping rules engine and Shipment lifecycle manager.
 *
 * Role boundary: ShippingService CALCULATES/DETERMINES shipping requirements
 * and manages the Shipment aggregate. It must NOT mutate Order or Payment state
 * directly — all state changes to those aggregates remain with their own services.
 *
 * Frozen continental US rules (do not invent other values):
 *   AK/HI                           → AK_HI_DEFERRED; quoted separately after PAID
 *   Continental US, NEXT_DAY_AIR    → $150 flat surcharge (regardless of subtotal)
 *   Continental US, GROUND, >= $500 → $0 (free shipping)
 *   Continental US, GROUND, < $500  → REQUIRES_MANUAL_QUOTE (no approved price)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShippingService {

    static final BigDecimal FREE_SHIPPING_THRESHOLD = new BigDecimal("500.00");
    static final BigDecimal NEXT_DAY_AIR_SURCHARGE  = new BigDecimal("150.00");
    private static final Set<String> AK_HI_STATES   = Set.of("AK", "HI");

    /**
     * All valid US state/territory postal abbreviations (upper-case).
     * This is the authoritative list for both checkout validation and the quote preview endpoint.
     */
    static final Set<String> VALID_US_STATES = Set.of(
            "AL","AK","AZ","AR","CA","CO","CT","DE","FL","GA",
            "HI","ID","IL","IN","IA","KS","KY","LA","ME","MD",
            "MA","MI","MN","MS","MO","MT","NE","NV","NH","NJ",
            "NM","NY","NC","ND","OH","OK","OR","PA","RI","SC",
            "SD","TN","TX","UT","VT","VA","WA","WV","WI","WY","DC");

    private final ShipmentRepository shipmentRepository;

    // ── Quote calculation (pure — no side effects) ────────────────────

    /**
     * Applies the frozen continental US shipping rules.
     * AK/HI orders bypass the continental pricing table entirely.
     * No money amounts are invented for cases without an approved price.
     */
    public ShippingQuote calculateQuote(String destinationState,
                                        ServiceLevel serviceLevel,
                                        BigDecimal subtotal) {
        if (isAkHi(destinationState)) {
            return ShippingQuote.akHiDeferred();
        }
        if (serviceLevel == ServiceLevel.NEXT_DAY_AIR) {
            // $150 flat surcharge, regardless of subtotal
            return ShippingQuote.resolved(NEXT_DAY_AIR_SURCHARGE);
        }
        // Continental US, Ground
        if (subtotal.compareTo(FREE_SHIPPING_THRESHOLD) >= 0) {
            return ShippingQuote.resolved(BigDecimal.ZERO);
        }
        // Ground < $500: no approved price — do not invent one
        return ShippingQuote.requiresManualQuote();
    }

    public boolean isAkHi(String state) {
        return state != null && AK_HI_STATES.contains(state.trim().toUpperCase());
    }

    /**
     * Enforces that the shipping destination is a supported US domestic address.
     * This is the single authoritative rule shared by checkout validation and the
     * quote preview endpoint. Throws {@link IllegalArgumentException} if invalid.
     *
     * @param country  ISO country code (must be "US", case-insensitive)
     * @param state    postal state abbreviation (must be in VALID_US_STATES)
     */
    public void validateDestination(String country, String state) {
        if (country == null || !country.trim().equalsIgnoreCase("US")) {
            throw new IllegalArgumentException(
                    "Only US domestic shipping is currently supported.");
        }
        if (state == null || state.isBlank()
                || !VALID_US_STATES.contains(state.trim().toUpperCase())) {
            throw new IllegalArgumentException(
                    "Unsupported shipping destination state: \"" + state + "\". " +
                    "Please enter a valid US state abbreviation.");
        }
    }

    // ── Shipment initialization (orchestrated by OrderService) ────────

    /**
     * Creates the Shipment for a continental US order at checkout time.
     * RESOLVED quotes produce NOT_REQUIRED; REQUIRES_MANUAL_QUOTE produces QUOTE_REQUIRED.
     * Must NOT be called for AK/HI orders.
     */
    @Transactional
    public Shipment initializeShipmentForOrder(Order order, ServiceLevel serviceLevel,
                                               ShippingQuote quote) {
        if (quote.isAkHiDeferred()) {
            throw new IllegalArgumentException(
                    "initializeShipmentForOrder must not be called for AK/HI order " +
                    order.getId() + ". Use initializeAkHiShipment after PAID transition.");
        }
        ShippingPaymentStatus status = quote.isResolved()
                ? ShippingPaymentStatus.NOT_REQUIRED
                : ShippingPaymentStatus.QUOTE_REQUIRED;

        Shipment shipment = Shipment.builder()
                .order(order)
                .serviceLevel(serviceLevel)
                .quotedShippingAmount(quote.isResolved() ? quote.amount() : null)
                .shippingPaymentStatus(status)
                .build();
        shipment = shipmentRepository.save(shipment);
        log.info("Shipment {} initialized for order {} (serviceLevel={}, status={})",
                shipment.getId(), order.getOrderNumber(), serviceLevel, status);
        return shipment;
    }

    /**
     * Creates a Shipment for an AK/HI order immediately after the Order transitions to PAID.
     * Idempotent: returns the existing Shipment if already created.
     * service_level defaults to GROUND — the admin determines the actual carrier service
     * when entering the shipping quote.
     */
    @Transactional
    public Shipment initializeAkHiShipment(Order order) {
        Optional<Shipment> existing = shipmentRepository.findByOrder_Id(order.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        Shipment shipment = Shipment.builder()
                .order(order)
                .serviceLevel(ServiceLevel.GROUND)
                .shippingPaymentStatus(ShippingPaymentStatus.QUOTE_REQUIRED)
                .build();
        shipment = shipmentRepository.save(shipment);
        log.info("AK/HI Shipment {} created for order {} → QUOTE_REQUIRED",
                shipment.getId(), order.getOrderNumber());
        return shipment;
    }

    // ── Admin operations ──────────────────────────────────────────────

    /**
     * Records the actual shipping cost: QUOTE_REQUIRED → QUOTED.
     * Stops at QUOTED — the admin must explicitly call requestShippingPayment()
     * to advance to PAYMENT_PENDING when presenting the charge to the customer.
     * Requires ROLE_ADMIN or ROLE_SENIOR_ADMIN (enforced at controller layer).
     */
    @Transactional
    public Shipment recordQuote(Long orderId, BigDecimal amount) {
        Shipment shipment = loadByOrderId(orderId);
        if (!shipment.getShippingPaymentStatus().canTransitionTo(ShippingPaymentStatus.QUOTED)) {
            throw new IllegalStateException(
                    "Shipment for order " + orderId + " cannot accept a quote in status " +
                    shipment.getShippingPaymentStatus() + ". Must be QUOTE_REQUIRED.");
        }
        shipment.setQuotedShippingAmount(amount);
        shipment.setQuotedAt(LocalDateTime.now());
        shipment.setShippingPaymentStatus(ShippingPaymentStatus.QUOTED);
        shipment = shipmentRepository.save(shipment);
        log.info("Shipment {} (order {}): quote recorded amount={} → QUOTED",
                shipment.getId(), orderId, amount);
        return shipment;
    }

    /**
     * Advances the shipping charge from QUOTED to PAYMENT_PENDING, representing that
     * the shipping cost has been communicated to the customer and payment is being requested.
     * Requires ROLE_ADMIN or ROLE_SENIOR_ADMIN (enforced at controller layer).
     */
    @Transactional
    public Shipment requestShippingPayment(Long orderId) {
        Shipment shipment = loadByOrderId(orderId);
        if (!shipment.getShippingPaymentStatus().canTransitionTo(ShippingPaymentStatus.PAYMENT_PENDING)) {
            throw new IllegalStateException(
                    "Shipment for order " + orderId + " cannot advance to PAYMENT_PENDING in status " +
                    shipment.getShippingPaymentStatus() + ". Must be QUOTED.");
        }
        shipment.setShippingPaymentStatus(ShippingPaymentStatus.PAYMENT_PENDING);
        shipment = shipmentRepository.save(shipment);
        log.info("Shipment {} (order {}): → PAYMENT_PENDING (payment requested from customer)",
                shipment.getId(), orderId);
        return shipment;
    }

    /**
     * Confirms supplemental shipping payment received out of band (PAYMENT_PENDING → PAID).
     *
     * CRITICAL invariant — this method must NOT:
     *   - create a Payment entity
     *   - call a PaymentGateway
     *   - create a PaymentEvent
     *   - modify Order status or Week 5 Payment records in any way
     * It only mutates the Shipment's shipping_payment_status.
     *
     * Requires ROLE_ADMIN or ROLE_SENIOR_ADMIN (enforced at controller layer).
     */
    @Transactional
    public Shipment confirmShippingPaymentReceived(Long orderId) {
        Shipment shipment = loadByOrderId(orderId);
        if (!shipment.getShippingPaymentStatus().canTransitionTo(ShippingPaymentStatus.PAID)) {
            throw new IllegalStateException(
                    "Shipment for order " + orderId + " cannot be marked PAID in status " +
                    shipment.getShippingPaymentStatus() + ". Must be PAYMENT_PENDING.");
        }
        shipment.setShippingPaymentStatus(ShippingPaymentStatus.PAID);
        shipment.setShippingPaidAt(LocalDateTime.now());
        shipment = shipmentRepository.save(shipment);
        log.info("Shipment {} (order {}): supplemental shipping payment confirmed PAID",
                shipment.getId(), orderId);
        return shipment;
    }

    /**
     * Records the carrier and tracking number for a shipment.
     * Does NOT set shippedAt — physical dispatch is a separate explicit action.
     * Guards fulfillment readiness: Order must be PAID and ShippingPaymentStatus must
     * be isFulfillmentReady() (NOT_REQUIRED, PAID, or WAIVED).
     * No carrier API validation — stores whatever the admin provides.
     * Requires ROLE_ADMIN or ROLE_SENIOR_ADMIN (enforced at controller layer).
     */
    @Transactional
    public Shipment recordTracking(Long orderId, String carrier, String trackingNumber) {
        Shipment shipment = loadByOrderId(orderId);
        Order order = shipment.getOrder(); // lazy-loaded within this transaction
        if (order.getStatus() != OrderStatus.PAID
                || !shipment.getShippingPaymentStatus().isFulfillmentReady()) {
            throw new IllegalStateException(
                    "Cannot record tracking for order " + orderId + ": order must be PAID " +
                    "and shipping fully resolved (NOT_REQUIRED, PAID, or WAIVED). " +
                    "Current: order=" + order.getStatus() +
                    ", shipping=" + shipment.getShippingPaymentStatus());
        }
        shipment.setCarrier(carrier);
        shipment.setTrackingNumber(trackingNumber);
        shipment = shipmentRepository.save(shipment);
        log.info("Shipment {} (order {}): tracking recorded carrier={}, number={} (shippedAt set separately at dispatch)",
                shipment.getId(), orderId, carrier, trackingNumber);
        return shipment;
    }

    // ── Queries ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Optional<Shipment> findByOrderId(Long orderId) {
        return shipmentRepository.findByOrder_Id(orderId);
    }

    // ── Private helpers ───────────────────────────────────────────────

    private Shipment loadByOrderId(Long orderId) {
        return shipmentRepository.findByOrder_Id(orderId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Shipment not found for order: " + orderId));
    }
}
