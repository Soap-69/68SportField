package com.cardshowcase;

import com.cardshowcase.model.entity.*;
import com.cardshowcase.notification.DeliveryOutcome;
import com.cardshowcase.notification.NotificationDispatcher;
import com.cardshowcase.notification.NotificationRenderer;
import com.cardshowcase.repository.*;
import com.cardshowcase.service.CustomerPrincipal;
import com.cardshowcase.service.InventoryService;
import com.cardshowcase.service.OrderService;
import com.cardshowcase.support.MailMessages;
import com.cardshowcase.support.RecordingMailSender;
import com.cardshowcase.support.Week8Fixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static com.cardshowcase.model.entity.NotificationStatus.*;
import static com.cardshowcase.model.entity.NotificationType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Week 8 notification log and delivery contract (test matrix items 1-9).
 * Every wait is a completion condition on the row status or a latch; no fixed sleeps.
 */
class NotificationIntegrationTest extends BaseIntegrationTest {

    @Autowired Week8Fixture fx;
    @Autowired JavaMailSender mailSender;
    @Autowired NotificationLogRepository logs;
    @Autowired NotificationDispatcher dispatcher;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentEventRepository paymentEventRepository;
    @Autowired InventoryAllocationRepository allocationRepository;
    @Autowired InventoryService inventoryService;
    @Autowired OrderService orderService;
    @Autowired PlatformTransactionManager txManager;
    @Autowired ObjectMapper objectMapper;

    RecordingMailSender mail;

    @BeforeEach
    void setUp() {
        fx.prepare(new BigDecimal("600.00"), 20);
        mail = (RecordingMailSender) mailSender;
        mail.reset();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private List<NotificationType> typesFor(long orderId) {
        return logs.findByOrder_IdOrderByIdAsc(orderId).stream().map(NotificationLog::getType).toList();
    }

    private List<MimeMessage> messages(String orderNumber, String subjectFragment) {
        return mail.sent().stream()
                .filter(m -> MailMessages.subject(m).contains(orderNumber))
                .filter(m -> MailMessages.subject(m).contains(subjectFragment))
                .toList();
    }

    private void awaitPaidTrio(long orderId) {
        fx.awaitSettled(orderId, ORDER_RECEIVED);
        fx.awaitSettled(orderId, PAYMENT_CONFIRMED);
        fx.awaitSettled(orderId, ADMIN_NEW_PAID_ORDER);
    }

    private NotificationLog pendingRow(long orderId, NotificationType type) {
        Order order = fx.order(orderId);
        return logs.save(NotificationLog.builder()
                .order(order).type(type).recipient(order.getGuestEmail())
                .status(PENDING).attempts(0).build());
    }

    private MvcResult resend(long orderId, String type) throws Exception {
        return fx.adminPlain(mockMvc, "/admin/api/orders/" + orderId + "/notifications/" + type + "/resend");
    }

    // ── 1. record idempotency ───────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"AK", "HI"})
    void unpaidAkHiOrder_emailShowsPendingSupplementalShipping(String state) throws Exception {
        long orderId = fx.guestCheckout(mockMvc, state, 1, UUID.randomUUID().toString());
        assertThat(fx.awaitSettled(orderId, ORDER_RECEIVED).getStatus()).isEqualTo(SENT);
        String html = MailMessages.html(messages(fx.order(orderId).getOrderNumber(), "received").get(0));
        String text = html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
        assertThat(text).contains("Shipping: $0.00", "Main-payment total: $600.00",
                "Supplemental shipping (not included above): Quote required")
                .doesNotContain("Free");
    }

    @Test
    void paidContinentalSupplementalShipping_staysOutsideMainPaymentTotal() throws Exception {
        fx.prepare(new BigDecimal("100.00"), 20);
        long orderId = fx.paidGuestOrder(mockMvc, "MA", 1);
        awaitPaidTrio(orderId);
        String base = "/admin/api/orders/" + orderId;
        assertThat(fx.adminJson(mockMvc, base + "/shipment/quote", Map.of("amount", "25.00"))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(fx.adminPlain(mockMvc, base + "/shipment/request-payment").getResponse().getStatus()).isEqualTo(200);
        fx.awaitSettled(orderId, SHIPPING_PAYMENT_REQUESTED);
        assertThat(fx.adminPlain(mockMvc, base + "/shipment/confirm-payment").getResponse().getStatus()).isEqualTo(200);
        assertThat(fx.adminJson(mockMvc, base + "/shipment/tracking",
                Map.of("carrier", "UPS", "trackingNumber", "REVIEW-CONTINENTAL"))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(fx.adminPlain(mockMvc, base + "/mark-processing").getResponse().getStatus()).isEqualTo(200);
        assertThat(fx.adminPlain(mockMvc, base + "/dispatch").getResponse().getStatus()).isEqualTo(200);
        assertThat(fx.awaitSettled(orderId, SHIPPED).getStatus()).isEqualTo(SENT);
        String html = MailMessages.html(messages(fx.order(orderId).getOrderNumber(), "has shipped").get(0));
        String text = html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
        assertThat(text).contains("Subtotal: $100.00", "Shipping: $0.00", "Tax: $0.00",
                "Main-payment total: $100.00", "Supplemental shipping (not included above): Paid separately — $25.00");
    }

    @Test
    void checkoutReplay_sameKey_createsOneOrderReceived() throws Exception {
        String key = UUID.randomUUID().toString();
        String token = fx.newGuestToken();
        long orderId = fx.guestCheckout(mockMvc, "MA", 1, key, token);

        MvcResult replay = fx.guestCheckoutReplay(mockMvc, "MA", key, token);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(replay.getResponse().getContentAsString()).get("id").asLong()).isEqualTo(orderId);

        assertThat(typesFor(orderId)).containsExactly(ORDER_RECEIVED);
        NotificationLog row = fx.awaitSettled(orderId, ORDER_RECEIVED);
        assertThat(row.getStatus()).isEqualTo(SENT);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(messages(fx.order(orderId).getOrderNumber(), "received")).hasSize(1);
    }

    @Test
    void confirmPaymentTwice_sameKey_createsOnePaymentConfirmedAndOneAdminRow() throws Exception {
        long orderId = fx.guestCheckout(mockMvc, "MA", 1, UUID.randomUUID().toString());
        String key = UUID.randomUUID().toString();
        assertThat(fx.manualConfirm(mockMvc, orderId, "SUCCESS", key).getResponse().getStatus()).isEqualTo(200);
        assertThat(fx.manualConfirm(mockMvc, orderId, "SUCCESS", key).getResponse().getStatus()).isEqualTo(200);

        assertThat(typesFor(orderId)).containsExactlyInAnyOrder(ORDER_RECEIVED, PAYMENT_CONFIRMED, ADMIN_NEW_PAID_ORDER);
        awaitPaidTrio(orderId);
        String number = fx.order(orderId).getOrderNumber();
        assertThat(messages(number, "Payment confirmed")).hasSize(1);
        assertThat(messages(number, "New paid order")).hasSize(1);
    }

    @Test
    void duplicateOrderAndType_rejectedByUniqueConstraint() throws Exception {
        long orderId = fx.guestCheckout(mockMvc, "MA", 1, UUID.randomUUID().toString());
        fx.awaitSettled(orderId, ORDER_RECEIVED);
        Order order = fx.order(orderId);
        assertThatThrownBy(() -> logs.saveAndFlush(NotificationLog.builder()
                .order(order).type(ORDER_RECEIVED).recipient("dup@example.com")
                .status(PENDING).attempts(0).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── 2. no record on failure paths ──────────────────────────────────

    @Test
    void declinedPayment_createsNoPaymentConfirmed() throws Exception {
        long orderId = fx.guestCheckout(mockMvc, "MA", 1, UUID.randomUUID().toString());
        assertThat(fx.manualConfirm(mockMvc, orderId, "DECLINED", UUID.randomUUID().toString())
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(typesFor(orderId)).containsExactly(ORDER_RECEIVED);
    }

    @Test
    void insufficientStockAtConfirm_createsNoPaymentConfirmed() throws Exception {
        long orderId = fx.guestCheckout(mockMvc, "MA", 1, UUID.randomUUID().toString());
        inventoryService.setStock(fx.variant.getId(), fx.location.getId(), 0);
        assertThat(fx.manualConfirm(mockMvc, orderId, "SUCCESS", UUID.randomUUID().toString())
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(fx.order(orderId).getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(typesFor(orderId)).containsExactly(ORDER_RECEIVED);
    }

    @Test
    void failedRefundExecution_createsNoRefundExecuted() throws Exception {
        long orderId = fx.paidGuestOrder(mockMvc, "MA", 1);
        allocationRepository.deleteAll(allocationRepository.findByOrderId(orderId));
        Order order = fx.order(orderId);
        MvcResult submit = fx.adminJson(mockMvc, "/admin/api/orders/" + orderId + "/refund-requests",
                Map.of("amount", order.getTotal(), "reason", "test"));
        long rrId = objectMapper.readTree(submit.getResponse().getContentAsString()).get("id").asLong();
        assertThat(fx.adminPlain(mockMvc, "/admin/api/refund-requests/" + rrId + "/approve")
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(fx.order(orderId).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(typesFor(orderId)).doesNotContain(REFUND_EXECUTED);
    }

    @Test
    void akHiFlow_onlyRequestPaymentAndDispatchEmit() throws Exception {
        long orderId = fx.paidGuestOrder(mockMvc, "HI", 1);
        assertThat(typesFor(orderId)).containsExactlyInAnyOrder(ORDER_RECEIVED, PAYMENT_CONFIRMED, ADMIN_NEW_PAID_ORDER);
        String base = "/admin/api/orders/" + orderId;

        assertThat(fx.adminJson(mockMvc, base + "/shipment/quote", Map.of("amount", "25.00")).getResponse().getStatus()).isEqualTo(200);
        assertThat(typesFor(orderId)).hasSize(3);

        assertThat(fx.adminPlain(mockMvc, base + "/shipment/request-payment").getResponse().getStatus()).isEqualTo(200);
        assertThat(typesFor(orderId)).contains(SHIPPING_PAYMENT_REQUESTED).hasSize(4);
        // Inspect the request while payment is pending; later delivery uses the current state.
        assertThat(fx.awaitSettled(orderId, SHIPPING_PAYMENT_REQUESTED).getStatus()).isEqualTo(SENT);

        assertThat(fx.adminPlain(mockMvc, base + "/shipment/confirm-payment").getResponse().getStatus()).isEqualTo(200);
        assertThat(fx.adminJson(mockMvc, base + "/shipment/tracking",
                Map.of("carrier", "UPS", "trackingNumber", "1Z-W8-TEST")).getResponse().getStatus()).isEqualTo(200);
        assertThat(fx.adminPlain(mockMvc, base + "/mark-processing").getResponse().getStatus()).isEqualTo(200);
        assertThat(typesFor(orderId)).hasSize(4);

        assertThat(fx.adminPlain(mockMvc, base + "/dispatch").getResponse().getStatus()).isEqualTo(200);
        assertThat(typesFor(orderId)).contains(SHIPPED).hasSize(5);

        NotificationLog req = fx.awaitSettled(orderId, SHIPPING_PAYMENT_REQUESTED);
        assertThat(req.getStatus()).isEqualTo(SENT);
        String number = fx.order(orderId).getOrderNumber();
        String reqHtml = MailMessages.html(messages(number, "Shipping payment requested").get(0));
        assertThat(reqHtml).contains("$25.00", "order@68sportfield.com", "confirm your payment method");

        assertThat(fx.awaitSettled(orderId, SHIPPED).getStatus()).isEqualTo(SENT);
        String shippedHtml = MailMessages.html(messages(number, "has shipped").get(0));
        assertThat(shippedHtml).contains("UPS").contains("1Z-W8-TEST");
        assertThat(shippedHtml.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " "))
                .contains("Subtotal: $600.00", "Shipping: $0.00", "Tax: $0.00",
                        "Main-payment total: $600.00",
                        "Supplemental shipping (not included above): Paid separately — $25.00");
    }

    // ── 3. business rollback → no record, no mail ──────────────────────

    @Test
    void rollbackAfterEnqueue_leavesNoRecordAndSendsNothing() throws Exception {
        long orderId = fx.paidGuestOrder(mockMvc, "MA", 1);
        awaitPaidTrio(orderId);
        String base = "/admin/api/orders/" + orderId;
        assertThat(fx.adminJson(mockMvc, base + "/shipment/tracking",
                Map.of("carrier", "UPS", "trackingNumber", "1Z-ROLLBACK")).getResponse().getStatus()).isEqualTo(200);
        assertThat(fx.adminPlain(mockMvc, base + "/mark-processing").getResponse().getStatus()).isEqualTo(200);
        int sentBefore = mail.sent().size();

        TransactionTemplate tx = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> tx.execute(status -> {
            orderService.dispatchShipment(orderId);
            throw new IllegalStateException("forced rollback after enqueue");
        })).hasMessageContaining("forced rollback");

        assertThat(fx.order(orderId).getStatus()).isEqualTo(OrderStatus.PROCESSING);
        assertThat(logs.findByOrder_IdAndType(orderId, SHIPPED)).isEmpty();
        String number = fx.order(orderId).getOrderNumber();
        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(3)).until(() ->
                logs.findByOrder_IdAndType(orderId, SHIPPED).isEmpty()
                        && messages(number, "has shipped").isEmpty()
                        && mail.sent().size() == sentBefore);
    }

    // ── 4. post-commit failure isolation ───────────────────────────────

    @Test
    void smtpFailure_businessStateCommitted_rowFailedWithError() throws Exception {
        long orderId = fx.guestCheckout(mockMvc, "MA", 1, UUID.randomUUID().toString());
        fx.awaitSettled(orderId, ORDER_RECEIVED);
        mail.failNext(2);
        assertThat(fx.manualConfirm(mockMvc, orderId, "SUCCESS", UUID.randomUUID().toString())
                .getResponse().getStatus()).isEqualTo(200);

        assertThat(fx.order(orderId).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(paymentRepository.findByOrder_Id(orderId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        for (NotificationType t : List.of(PAYMENT_CONFIRMED, ADMIN_NEW_PAID_ORDER)) {
            NotificationLog row = fx.awaitSettled(orderId, t);
            assertThat(row.getStatus()).as(t.name()).isEqualTo(FAILED);
            assertThat(row.getAttempts()).isEqualTo(1);
            assertThat(row.getLastError()).contains("Simulated SMTP failure");
        }
    }

    // ── 6. delivery exclusion (notifications enabled) ──────────────────

    @Test
    void twoAutomaticSenders_onePendingRow_exactlyOneClaimAndOneMessage() throws Exception {
        long orderId = fx.paidGuestOrder(mockMvc, "MA", 1);
        awaitPaidTrio(orderId);
        NotificationLog row = pendingRow(orderId, SHIPPED);
        CountDownLatch entered = mail.holdSends();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<DeliveryOutcome> a = pool.submit(() -> dispatcher.deliver(row.getId()));
            Future<DeliveryOutcome> b = pool.submit(() -> dispatcher.deliver(row.getId()));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            NotificationLog sending = fx.awaitStatus(row.getId(), SENDING);
            assertThat(sending.getAttempts()).isEqualTo(1);
            mail.releaseGate();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(DeliveryOutcome.SENT, DeliveryOutcome.NOT_CLAIMED);
        } finally {
            pool.shutdownNow();
        }
        NotificationLog done = fx.awaitStatus(row.getId(), SENT);
        assertThat(done.getAttempts()).isEqualTo(1);
        assertThat(messages(fx.order(orderId).getOrderNumber(), "has shipped")).hasSize(1);
    }

    @Test
    void manualResendDuringAutomaticSend_returns409_andOnlyOneMessage() throws Exception {
        CountDownLatch entered = mail.holdSends();
        long orderId;
        try {
            orderId = fx.guestCheckout(mockMvc, "MA", 1, UUID.randomUUID().toString());
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            NotificationLog sending = fx.awaitStatus(fx.log(orderId, ORDER_RECEIVED).getId(), SENDING);
            assertThat(sending.getAttempts()).isEqualTo(1);

            MvcResult r = resend(orderId, "ORDER_RECEIVED");
            assertThat(r.getResponse().getStatus()).isEqualTo(409);
            assertThat(fx.log(orderId, ORDER_RECEIVED).getAttempts()).isEqualTo(1);
        } finally {
            mail.releaseGate();
        }
        NotificationLog done = fx.awaitSettled(orderId, ORDER_RECEIVED);
        assertThat(done.getStatus()).isEqualTo(SENT);
        assertThat(done.getAttempts()).isEqualTo(1);
        assertThat(messages(fx.order(orderId).getOrderNumber(), "received")).hasSize(1);
    }

    @Test
    void twoConcurrentManualResends_ofFailedRow_oneWinsOne409() throws Exception {
        long orderId = fx.paidGuestOrder(mockMvc, "MA", 1);
        awaitPaidTrio(orderId);
        NotificationLog row = pendingRow(orderId, SHIPPED);
        mail.failNext(1);
        assertThat(dispatcher.deliver(row.getId())).isEqualTo(DeliveryOutcome.FAILED);
        assertThat(fx.log(orderId, SHIPPED).getStatus()).isEqualTo(FAILED);

        CountDownLatch entered = mail.holdSends();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses;
        try {
            Callable<Integer> call = () -> resend(orderId, "SHIPPED").getResponse().getStatus();
            Future<Integer> a = pool.submit(call);
            Future<Integer> b = pool.submit(call);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            mail.releaseGate();
            statuses = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        NotificationLog done = fx.awaitStatus(row.getId(), SENT);
        assertThat(done.getAttempts()).isEqualTo(2);
        assertThat(messages(fx.order(orderId).getOrderNumber(), "has shipped")).hasSize(1);
    }

    @Test
    void failedOrderReceived_resentAfterPayment_doesNotClaimPaymentPending() throws Exception {
        mail.failNext(1);
        long orderId = fx.guestCheckout(mockMvc, "MA", 1, UUID.randomUUID().toString());
        NotificationLog failed = fx.awaitSettled(orderId, ORDER_RECEIVED);
        assertThat(failed.getStatus()).isEqualTo(FAILED);
        assertThat(failed.getAttempts()).isEqualTo(1);
        String number = fx.order(orderId).getOrderNumber();
        assertThat(messages(number, "received")).isEmpty();

        assertThat(fx.manualConfirm(mockMvc, orderId, "SUCCESS", UUID.randomUUID().toString())
                .getResponse().getStatus()).isEqualTo(200);
        awaitPaidTrio(orderId);
        assertThat(fx.order(orderId).getStatus()).isEqualTo(OrderStatus.PAID);
        int rowsBefore = logs.findByOrder_IdOrderByIdAsc(orderId).size();
        assertThat(resend(orderId, "ORDER_RECEIVED").getResponse().getStatus()).isEqualTo(200);

        NotificationLog sent = fx.awaitSettled(orderId, ORDER_RECEIVED);
        assertThat(sent.getStatus()).isEqualTo(SENT);
        assertThat(sent.getId()).isEqualTo(failed.getId());
        assertThat(sent.getAttempts()).isEqualTo(2);
        assertThat(logs.findByOrder_IdOrderByIdAsc(orderId)).hasSize(rowsBefore);
        List<MimeMessage> received = messages(number, "received");
        assertThat(received).hasSize(1);
        assertThat(MailMessages.subject(received.get(0))).isEqualTo("Order " + number + " received");
        assertThat(MailMessages.html(received.get(0)))
                .contains("We have received your order.", number, "$600.00")
                .doesNotContain("Payment has not been made yet.", "once the payment is complete", "payment pending");
        assertThat(resend(orderId, "ORDER_RECEIVED").getResponse().getStatus()).isEqualTo(409);
        assertThat(messages(number, "received")).hasSize(1);
    }

    @Test
    void failedShippingRequest_resentAfterShippingPaid_showsPaidWithoutRequestingPayment() throws Exception {
        long orderId = fx.paidGuestOrder(mockMvc, "HI", 1);
        awaitPaidTrio(orderId);
        String number = fx.order(orderId).getOrderNumber();
        String base = "/admin/api/orders/" + orderId;
        assertThat(fx.adminJson(mockMvc, base + "/shipment/quote", Map.of("amount", "35.00"))
                .getResponse().getStatus()).isEqualTo(200);
        mail.failNext(1);
        assertThat(fx.adminPlain(mockMvc, base + "/shipment/request-payment").getResponse().getStatus()).isEqualTo(200);
        NotificationLog failed = fx.awaitSettled(orderId, SHIPPING_PAYMENT_REQUESTED);
        assertThat(failed.getStatus()).isEqualTo(FAILED);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(messages(number, "Shipping")).isEmpty();

        assertThat(fx.adminPlain(mockMvc, base + "/shipment/confirm-payment").getResponse().getStatus()).isEqualTo(200);
        int rowsBefore = logs.findByOrder_IdOrderByIdAsc(orderId).size();
        assertThat(resend(orderId, "SHIPPING_PAYMENT_REQUESTED").getResponse().getStatus()).isEqualTo(200);

        NotificationLog sent = fx.awaitSettled(orderId, SHIPPING_PAYMENT_REQUESTED);
        assertThat(sent.getStatus()).isEqualTo(SENT);
        assertThat(sent.getId()).isEqualTo(failed.getId());
        assertThat(sent.getAttempts()).isEqualTo(2);
        assertThat(logs.findByOrder_IdOrderByIdAsc(orderId)).hasSize(rowsBefore);
        List<MimeMessage> shipping = messages(number, "Shipping");
        assertThat(shipping).hasSize(1);
        assertThat(MailMessages.subject(shipping.get(0))).isEqualTo("Shipping update for order " + number);
        assertThat(MailMessages.html(shipping.get(0)))
                .contains("Paid separately — $35.00", number)
                .doesNotContain("Shipping payment requested", "Please reply to", "confirm your payment method", "Awaiting payment");
        assertThat(resend(orderId, "SHIPPING_PAYMENT_REQUESTED").getResponse().getStatus()).isEqualTo(409);
        assertThat(messages(number, "Shipping")).hasSize(1);
    }

    // ── 7. resend semantics and authorization ──────────────────────────

    @Test
    void resend_semantics_sentRows_unknownTypeOrOrder_noNewRows() throws Exception {
        long orderId = fx.paidGuestOrder(mockMvc, "MA", 1);
        awaitPaidTrio(orderId);
        int rowsBefore = logs.findByOrder_IdOrderByIdAsc(orderId).size();

        MvcResult sent = resend(orderId, "ORDER_RECEIVED");
        assertThat(sent.getResponse().getStatus()).isEqualTo(409);
        assertThat(sent.getResponse().getContentAsString()).contains("SENT");

        assertThat(resend(orderId, "BOGUS_TYPE").getResponse().getStatus()).isEqualTo(404);
        assertThat(resend(orderId, "SHIPPED").getResponse().getStatus()).isEqualTo(404); // no row for this order
        assertThat(resend(999_999L, "ORDER_RECEIVED").getResponse().getStatus()).isEqualTo(404);
        assertThat(logs.findByOrder_IdOrderByIdAsc(orderId)).hasSize(rowsBefore);
    }

    @Test
    void resend_authorization_anonymousCustomerAdminSeniorAndMissingCsrf() throws Exception {
        long orderId = fx.paidGuestOrder(mockMvc, "MA", 1);
        awaitPaidTrio(orderId);
        NotificationLog row = pendingRow(orderId, SHIPPED);
        mail.failNext(1);
        assertThat(dispatcher.deliver(row.getId())).isEqualTo(DeliveryOutcome.FAILED);
        String url = "/admin/api/orders/" + orderId + "/notifications/SHIPPED/resend";

        int anonymous = mockMvc.perform(post(url).with(csrf())).andReturn().getResponse().getStatus();
        assertThat(anonymous).isBetween(300, 399);

        Customer customer = fx.registerCustomer("resend-cust");
        assertThat(mockMvc.perform(post(url).with(csrf()).with(user(new CustomerPrincipal(customer))))
                .andReturn().getResponse().getStatus()).isEqualTo(403);

        // Real token path: an authenticated admin without any CSRF token is rejected by CsrfFilter.
        assertThat(mockMvc.perform(post(url).with(user(fx.seniorAdmin.getUsername()).roles("SENIOR_ADMIN")))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(fx.log(orderId, SHIPPED).getAttempts()).isEqualTo(1);

        MvcResult admin = mockMvc.perform(post(url).with(csrf())
                .with(user(fx.regularAdmin.getUsername()).roles("ADMIN"))).andReturn();
        assertThat(admin.getResponse().getStatus()).isEqualTo(200);
        assertThat(fx.awaitStatus(row.getId(), SENT).getAttempts()).isEqualTo(2);

        // SENIOR_ADMIN on the now-SENT row: 409, nothing resent.
        assertThat(resend(orderId, "SHIPPED").getResponse().getStatus()).isEqualTo(409);
        assertThat(fx.log(orderId, SHIPPED).getAttempts()).isEqualTo(2);
    }

    // ── 8. recipient resolution ────────────────────────────────────────

    @Test
    void recipients_guestCustomerAdmin_andReplyTo() throws Exception {
        long guestOrderId = fx.paidGuestOrder(mockMvc, "MA", 1);
        awaitPaidTrio(guestOrderId);
        Order guestOrder = fx.order(guestOrderId);
        assertThat(fx.log(guestOrderId, ORDER_RECEIVED).getRecipient()).isEqualTo(guestOrder.getGuestEmail());
        assertThat(fx.log(guestOrderId, ADMIN_NEW_PAID_ORDER).getRecipient()).isEqualTo("test@test.com");
        MimeMessage received = messages(guestOrder.getOrderNumber(), "received").get(0);
        assertThat(MailMessages.firstRecipient(received)).isEqualTo(guestOrder.getGuestEmail());
        assertThat(MailMessages.replyTo(received)).isEqualTo("order@68sportfield.com");
        MimeMessage adminMsg = messages(guestOrder.getOrderNumber(), "New paid order").get(0);
        assertThat(MailMessages.firstRecipient(adminMsg)).isEqualTo("test@test.com");

        Customer customer = fx.registerCustomer("recipient");
        long custOrderId = fx.customerCheckout(mockMvc, customer, "MA", 1);
        NotificationLog custRow = fx.awaitSettled(custOrderId, ORDER_RECEIVED);
        assertThat(custRow.getRecipient()).isEqualTo(customer.getEmail());
        assertThat(custRow.getStatus()).isEqualTo(SENT);
    }

    // ── 9. content and leaks ───────────────────────────────────────────

    @Test
    void content_unpaidSentence_noAffirmativeClaims_noSensitiveFields_idsOnlyInLinks() throws Exception {
        long orderId = fx.guestCheckout(mockMvc, "MA", 1, UUID.randomUUID().toString());
        assertThat(fx.awaitSettled(orderId, ORDER_RECEIVED).getStatus()).isEqualTo(SENT);
        assertThat(fx.manualConfirm(mockMvc, orderId, "SUCCESS", UUID.randomUUID().toString())
                .getResponse().getStatus()).isEqualTo(200);
        awaitPaidTrio(orderId);
        Order order = fx.order(orderId);
        Payment payment = paymentRepository.findByOrder_Id(orderId).orElseThrow();
        String number = order.getOrderNumber();

        String receivedHtml = MailMessages.html(messages(number, "received").get(0));
        assertThat(receivedHtml)
                .contains(NotificationRenderer.ORDER_RECEIVED_UNPAID_SENTENCE)
                .contains(number)
                .contains("$600.00")
                .doesNotContain("Payment received")
                .doesNotContain("Payment confirmed")
                .doesNotContain("Thank you for your payment")
                .doesNotContain("reserved for you")
                .doesNotContain("has shipped")
                .doesNotContain("has been shipped")
                .doesNotContain("/account/orders/");  // guest: no customer link

        List<String> forbidden = new java.util.ArrayList<>(List.of(
                order.getIdempotencyKey(), order.getGuestCartTokenHash(), order.getRequestFingerprint(),
                payment.getProviderPaymentId()));
        paymentEventRepository.findByPayment_IdOrderByCreatedAtAsc(payment.getId())
                .forEach(e -> forbidden.add(e.getEventId()));
        assertThat(forbidden).allSatisfy(s -> assertThat(s).isNotBlank());

        String adminLink = "http://localhost/admin/orders/" + orderId;
        for (MimeMessage m : mail.sent().stream().filter(x -> MailMessages.subject(x).contains(number)).toList()) {
            String html = MailMessages.html(m);
            String subject = MailMessages.subject(m);
            for (String s : forbidden) {
                assertThat(html).as(subject).doesNotContain(s);
                assertThat(subject).doesNotContain(s);
            }
            String stripped = html.replace(adminLink, "");
            assertThat(stripped).as(subject).doesNotContain("/orders/" + orderId).doesNotContain("#" + orderId);
        }
        String adminHtml = MailMessages.html(messages(number, "New paid order").get(0));
        assertThat(adminHtml).contains(adminLink);

        Customer customer = fx.registerCustomer("content");
        long custOrderId = fx.customerCheckout(mockMvc, customer, "MA", 1);
        fx.awaitSettled(custOrderId, ORDER_RECEIVED);
        String custNumber = fx.order(custOrderId).getOrderNumber();
        assertThat(MailMessages.html(messages(custNumber, "received").get(0)))
                .contains("http://localhost/account/orders/" + custOrderId)
                .contains("Casey");
    }
}
