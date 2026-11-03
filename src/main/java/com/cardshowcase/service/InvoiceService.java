package com.cardshowcase.service;

import com.cardshowcase.exception.InvoiceUnavailableException;
import com.cardshowcase.model.entity.*;
import com.cardshowcase.notification.BusinessTimeFormatter;
import com.cardshowcase.notification.NotificationSettings;
import com.cardshowcase.repository.*;
import com.lowagie.text.*;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.FontSelector;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Main-payment invoice, rendered on demand and never stored.
 *
 * Amounts are the order snapshot ({@code subtotal}, {@code shippingAmount}, {@code taxAmount},
 * {@code total}); supplemental shipping is not part of this document and paying it later
 * does not change it. After a refund the amounts stay and the document is marked refunded.
 * Two renders of the same order differ in PDF metadata (creation time); equality is
 * defined on content, not bytes.
 */
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final ShipmentRepository shipmentRepository;
    private final NotificationSettings settings;
    private final BusinessTimeFormatter time;

    private static final BaseFont INVOICE_FONT = loadInvoiceFont("NotoSans-Regular.ttf");
    private static final BaseFont CJK_FONT = loadInvoiceFont("NotoSansCJKsc-Regular.otf");
    private static final Font TITLE  = new Font(INVOICE_FONT, 18, Font.BOLD);
    private static final Font HEAD   = new Font(INVOICE_FONT, 10, Font.BOLD);
    private static final Font BODY   = new Font(INVOICE_FONT, 10, Font.NORMAL);
    private static final Font SMALL  = new Font(INVOICE_FONT, 8, Font.NORMAL);

    private static BaseFont loadInvoiceFont(String name) {
        try {
            byte[] bytes = new ClassPathResource("fonts/" + name).getContentAsByteArray();
            return BaseFont.createFont(name, BaseFont.IDENTITY_H,
                    BaseFont.EMBEDDED, true, bytes, null);
        } catch (IOException | DocumentException e) {
            throw new IllegalStateException("Cannot load bundled invoice font", e);
        }
    }

    public static boolean isAvailable(Payment payment) {
        return payment != null
                && (payment.getStatus() == PaymentStatus.SUCCEEDED
                 || payment.getStatus() == PaymentStatus.REFUNDED);
    }

    public static String fileName(Order order) {
        return "invoice-" + order.getOrderNumber() + ".pdf";
    }

    @Transactional(readOnly = true)
    public byte[] render(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        Payment payment = paymentRepository.findByOrder_Id(orderId).orElse(null);
        if (!isAvailable(payment)) {
            throw new InvoiceUnavailableException(orderId);
        }
        List<OrderItem> items = orderItemRepository.findByOrder_IdOrderByIdAsc(orderId);
        Shipment shipment = shipmentRepository.findByOrder_Id(orderId).orElse(null);
        List<PaymentEvent> events = paymentEventRepository.findByPayment_IdOrderByCreatedAtAsc(payment.getId());
        LocalDateTime paidAt = eventTime(events, PaymentEventType.PAYMENT_SUCCEEDED);
        LocalDateTime refundedAt = eventTime(events, PaymentEventType.PAYMENT_REFUNDED);
        boolean refunded = payment.getStatus() == PaymentStatus.REFUNDED;

        try {
            Document doc = new Document(PageSize.LETTER, 50, 50, 50, 50);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfWriter.getInstance(doc, out);
            doc.open();

            if (settings.hasMerchantDisplayName()) {
                doc.add(paragraph(settings.getMerchantDisplayName(), HEAD));
            }
            doc.add(paragraph(refunded ? "INVOICE — REFUNDED" : "INVOICE", TITLE));
            doc.add(paragraph("Invoice number: " + order.getOrderNumber(), BODY));
            doc.add(paragraph("Order date: " + time.format(order.getCreatedAt()), BODY));
            if (paidAt != null) {
                doc.add(paragraph("Payment date: " + time.format(paidAt), BODY));
            }
            doc.add(Chunk.NEWLINE);

            PdfPTable addresses = new PdfPTable(2);
            addresses.setWidthPercentage(100);
            addresses.addCell(cell("Bill to\n" + billTo(order), BODY, false));
            addresses.addCell(cell("Ship to\n" + shipTo(order), BODY, false));
            doc.add(addresses);
            doc.add(Chunk.NEWLINE);

            PdfPTable table = new PdfPTable(new float[]{4f, 2.2f, 0.8f, 1.4f, 1.4f});
            table.setWidthPercentage(100);
            for (String h : new String[]{"Item", "SKU", "Qty", "Unit price", "Line total"}) {
                table.addCell(cell(h, HEAD, true));
            }
            for (OrderItem item : items) {
                table.addCell(cell(item.getProductName() + " (" + item.getVariantTypeSnapshot() + ")", BODY, true));
                table.addCell(cell(item.getSkuSnapshot() == null ? "" : item.getSkuSnapshot(), BODY, true));
                table.addCell(cell(String.valueOf(item.getQuantity()), BODY, true));
                table.addCell(cell(money(item.getUnitPrice()), BODY, true));
                table.addCell(cell(money(item.getLineSubtotal()), BODY, true));
            }
            doc.add(table);
            doc.add(Chunk.NEWLINE);

            doc.add(paragraph("Subtotal: " + money(order.getSubtotal()), BODY));
            doc.add(paragraph("Shipping: " + money(order.getShippingAmount()), BODY));
            if (shipment != null
                    && shipment.getShippingPaymentStatus() != ShippingPaymentStatus.NOT_REQUIRED
                    && shipment.getShippingPaymentStatus() != ShippingPaymentStatus.WAIVED) {
                doc.add(paragraph("Shipping billed separately", BODY));
            }
            doc.add(paragraph("Tax: " + money(order.getTaxAmount()), BODY));
            doc.add(paragraph("Total: " + money(order.getTotal()), HEAD));
            doc.add(Chunk.NEWLINE);

            if (refunded) {
                doc.add(paragraph("Refunded on " + (refundedAt != null ? time.format(refundedAt) : "(date unavailable)"), HEAD));
            } else {
                doc.add(paragraph("Paid via " + payment.getProvider(), BODY));
            }

            if (settings.hasMerchantLegalName() || settings.hasMerchantAddress()) {
                doc.add(Chunk.NEWLINE);
                if (settings.hasMerchantLegalName()) doc.add(paragraph(settings.getMerchantLegalName(), SMALL));
                if (settings.hasMerchantAddress())   doc.add(paragraph(settings.getMerchantAddress(), SMALL));
            }

            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Invoice rendering failed for order " + orderId, e);
        }
    }

    private static LocalDateTime eventTime(List<PaymentEvent> events, PaymentEventType type) {
        return events.stream()
                .filter(e -> e.getEventType() == type)
                .map(PaymentEvent::getCreatedAt)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private static Paragraph paragraph(String text, Font font) {
        return new Paragraph(phrase(text, font));
    }

    /** Use the bundled Latin and CJK fonts for mixed names, addresses and item text. */
    private static Phrase phrase(String text, Font font) {
        FontSelector selector = new FontSelector();
        selector.addFont(font);
        selector.addFont(new Font(CJK_FONT, font.getSize(), font.getStyle()));
        return selector.process(text);
    }

    private static PdfPCell cell(String text, Font font, boolean border) {
        PdfPCell c = new PdfPCell(phrase(text, font));
        c.setPadding(4f);
        if (!border) c.setBorder(Rectangle.NO_BORDER);
        return c;
    }

    static String money(BigDecimal amount) {
        return "$" + amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String billTo(Order o) {
        return address(o.getBillingFirstName(), o.getBillingLastName(), o.getBillingAddressLine1(),
                o.getBillingAddressLine2(), o.getBillingCity(), o.getBillingState(), o.getBillingZip(), o.getBillingCountry());
    }

    private static String shipTo(Order o) {
        return address(o.getShippingFirstName(), o.getShippingLastName(), o.getShippingAddressLine1(),
                o.getShippingAddressLine2(), o.getShippingCity(), o.getShippingState(), o.getShippingZip(), o.getShippingCountry());
    }

    private static String address(String first, String last, String line1, String line2,
                                  String city, String state, String zip, String country) {
        StringBuilder sb = new StringBuilder();
        sb.append(nvl(first)).append(' ').append(nvl(last)).append('\n');
        sb.append(nvl(line1)).append('\n');
        if (line2 != null && !line2.isBlank()) sb.append(line2).append('\n');
        sb.append(nvl(city)).append(", ").append(nvl(state)).append(' ').append(nvl(zip)).append('\n');
        sb.append(nvl(country));
        return sb.toString();
    }

    private static String nvl(String s) { return s == null ? "" : s; }
}
