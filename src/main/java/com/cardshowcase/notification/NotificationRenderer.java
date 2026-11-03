package com.cardshowcase.notification;

import com.cardshowcase.model.entity.*;
import com.cardshowcase.repository.*;
import com.cardshowcase.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Builds subject, HTML body and (for PAYMENT_CONFIRMED) the invoice attachment.
 * Runs in its own read-only transaction so lazy associations are loaded here,
 * on the async thread, and nothing crosses the thread boundary but the row id.
 */
@Service
@RequiredArgsConstructor
public class NotificationRenderer {

    /** The one place the unpaid statement lives; the template and the test both use it. */
    public static final String ORDER_RECEIVED_UNPAID_SENTENCE =
            "We have received your order. Payment has not been made yet.";

    private final NotificationLogRepository logRepository;
    private final OrderItemRepository orderItemRepository;
    private final ShipmentRepository shipmentRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final TemplateEngine templateEngine;
    private final InvoiceService invoiceService;
    private final NotificationSettings settings;
    private final BusinessTimeFormatter time;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public RenderedNotification render(long logId) {
        NotificationLog log = logRepository.findById(logId).orElseThrow();
        Order order = log.getOrder();
        Long orderId = order.getId();
        List<OrderItem> items = orderItemRepository.findByOrder_IdOrderByIdAsc(orderId);
        Shipment shipment = shipmentRepository.findByOrder_Id(orderId).orElse(null);
        Payment payment = paymentRepository.findByOrder_Id(orderId).orElse(null);

        boolean orderPaymentPending = order.getStatus() == OrderStatus.PENDING_PAYMENT;
        boolean shippingPaymentPending = shipment != null
                && shipment.getShippingPaymentStatus() == ShippingPaymentStatus.PAYMENT_PENDING;
        Context ctx = new Context(Locale.US);
        ctx.setVariable("orderPaymentPending", orderPaymentPending);
        ctx.setVariable("shippingPaymentPending", shippingPaymentPending);
        ctx.setVariable("order", order);
        ctx.setVariable("items", items);
        ctx.setVariable("shipment", shipment);
        ctx.setVariable("orderDate", time.format(order.getCreatedAt()));
        ctx.setVariable("shippingLine", shippingLine(order, shipment));
        ctx.setVariable("hasSupplementalShipping", shipment == null
                || shipment.getShippingPaymentStatus() != ShippingPaymentStatus.NOT_REQUIRED);
        ctx.setVariable("recipientName", recipientName(order));
        ctx.setVariable("customerLink", order.getCustomer() != null
                ? settings.getBaseUrl() + "/account/orders/" + orderId : null);
        ctx.setVariable("adminLink", settings.getBaseUrl() + "/admin/orders/" + orderId);
        ctx.setVariable("merchantName", settings.hasMerchantDisplayName() ? settings.getMerchantDisplayName() : null);
        ctx.setVariable("replyTo", settings.getReplyTo());
        ctx.setVariable("unpaidSentence", ORDER_RECEIVED_UNPAID_SENTENCE);
        ctx.setVariable("paymentAmount", payment != null ? payment.getAmount() : null);
        ctx.setVariable("refundDate", refundDate(payment));

        NotificationType type = log.getType();
        String html = templateEngine.process("email/" + templateFor(type), ctx);
        byte[] attachment = type == NotificationType.PAYMENT_CONFIRMED ? invoiceService.render(orderId) : null;
        String attachmentName = attachment != null ? InvoiceService.fileName(order) : null;
        return new RenderedNotification(log.getRecipient(), subjectFor(type, order, shippingPaymentPending), html, attachmentName, attachment);
    }

    private static String templateFor(NotificationType type) {
        return switch (type) {
            case ORDER_RECEIVED             -> "order-received";
            case PAYMENT_CONFIRMED          -> "payment-confirmed";
            case ADMIN_NEW_PAID_ORDER       -> "admin-new-paid-order";
            case SHIPPING_PAYMENT_REQUESTED -> "shipping-payment-requested";
            case SHIPPED                    -> "shipped";
            case REFUND_EXECUTED            -> "refund-executed";
        };
    }

    private static String subjectFor(NotificationType type, Order order, boolean shippingPaymentPending) {
        String n = order.getOrderNumber();
        return switch (type) {
            case ORDER_RECEIVED             -> "Order " + n + " received";
            case PAYMENT_CONFIRMED          -> "Payment confirmed for order " + n;
            case ADMIN_NEW_PAID_ORDER       -> "New paid order " + n;
            case SHIPPING_PAYMENT_REQUESTED -> (shippingPaymentPending ? "Shipping payment requested for order " : "Shipping update for order ") + n;
            case SHIPPED                    -> "Order " + n + " has shipped";
            case REFUND_EXECUTED            -> "Refund issued for order " + n;
        };
    }

    private static String recipientName(Order order) {
        if (order.getCustomer() != null) {
            return order.getCustomer().getFirstName();
        }
        return order.getGuestName();
    }

    /** Supplemental charges stay separate from the immutable main-payment totals. */
    private static String shippingLine(Order order, Shipment shipment) {
        // AK/HI orders receive their Shipment only when the main payment succeeds.
        if (shipment == null) {
            return "Quote required";
        }
        if (shipment.getShippingPaymentStatus() == ShippingPaymentStatus.NOT_REQUIRED) {
            return order.getShippingAmount().signum() == 0 ? "Free" : money(order.getShippingAmount());
        }
        return switch (shipment.getShippingPaymentStatus()) {
            case QUOTE_REQUIRED  -> "Quote required";
            case QUOTED, PAYMENT_PENDING -> "Awaiting payment — " + money(shipment.getQuotedShippingAmount());
            case PAID            -> "Paid separately — " + money(shipment.getQuotedShippingAmount());
            case WAIVED          -> "Waived";
            case NOT_REQUIRED    -> money(order.getShippingAmount());
        };
    }

    private String refundDate(Payment payment) {
        if (payment == null || payment.getStatus() != PaymentStatus.REFUNDED) {
            return null;
        }
        return paymentEventRepository.findByPayment_IdOrderByCreatedAtAsc(payment.getId()).stream()
                .filter(e -> e.getEventType() == PaymentEventType.PAYMENT_REFUNDED)
                .map(PaymentEvent::getCreatedAt)
                .reduce((first, second) -> second)
                .map(time::format)
                .orElse(null);
    }

    private static String money(BigDecimal amount) {
        return "$" + amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
