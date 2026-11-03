package com.cardshowcase;

import com.cardshowcase.model.entity.*;
import com.cardshowcase.model.dto.CheckoutRequest;
import com.cardshowcase.repository.CartRepository;
import com.cardshowcase.repository.ProductRepository;
import com.cardshowcase.service.CartService;
import com.cardshowcase.repository.NotificationLogRepository;
import com.cardshowcase.service.CustomerPrincipal;
import com.cardshowcase.support.MailMessages;
import com.cardshowcase.support.RecordingMailSender;
import com.cardshowcase.support.Week8Fixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.cardshowcase.model.entity.NotificationType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/** Week 8 main-payment invoice (test matrix items 10-13). */
class InvoiceIntegrationTest extends BaseIntegrationTest {

    @Autowired Week8Fixture fx;
    @Autowired JavaMailSender mailSender;
    @Autowired NotificationLogRepository logs;
    @Autowired ObjectMapper objectMapper;
    @Autowired CartRepository cartRepository;
    @Autowired CartService cartService;
    @Autowired ProductRepository productRepository;

    RecordingMailSender mail;
    Customer owner;
    Customer other;

    @BeforeEach
    void setUp() {
        fx.prepare(new BigDecimal("600.00"), 20);
        mail = (RecordingMailSender) mailSender;
        mail.reset();
        owner = fx.registerCustomer("owner");
        other = fx.registerCustomer("other");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    static String pdfText(byte[] bytes) throws Exception {
        PdfReader reader = new PdfReader(bytes);
        try {
            assertThat(reader.getNumberOfPages()).isGreaterThanOrEqualTo(1);
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= reader.getNumberOfPages(); i++) {
                sb.append(extractor.getTextFromPage(i)).append('\n');
            }
            return sb.toString();
        } finally {
            reader.close();
        }
    }

    private MvcResult customerInvoice(Customer customer, long orderId) throws Exception {
        return mockMvc.perform(get("/account/orders/" + orderId + "/invoice.pdf")
                .with(user(new CustomerPrincipal(customer)))).andReturn();
    }

    private MvcResult adminInvoice(long orderId) throws Exception {
        return mockMvc.perform(get("/admin/orders/" + orderId + "/invoice.pdf")
                .with(user(fx.regularAdmin.getUsername()).roles("ADMIN"))).andReturn();
    }

    private long paidCustomerOrder(Customer customer, String state) throws Exception {
        long orderId = fx.customerCheckout(mockMvc, customer, state, 1);
        MvcResult r = fx.manualConfirm(mockMvc, orderId, "SUCCESS", java.util.UUID.randomUUID().toString());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return orderId;
    }

    private MimeMessage paymentConfirmedMessage(String orderNumber) {
        List<MimeMessage> found = mail.sent().stream()
                .filter(m -> MailMessages.subject(m).equals("Payment confirmed for order " + orderNumber))
                .toList();
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    // ── 10. amounts fixed at main payment; supplemental shipping never changes them ──

    @Test
    void invoiceAmounts_unchangedBySupplementalShippingPayment_andMatchEmailAttachment() throws Exception {
        long orderId = paidCustomerOrder(owner, "HI");
        fx.awaitSettled(orderId, PAYMENT_CONFIRMED);
        Order order = fx.order(orderId);

        MvcResult first = customerInvoice(owner, orderId);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        String text1 = pdfText(first.getResponse().getContentAsByteArray());

        String base = "/admin/api/orders/" + orderId + "/shipment";
        assertThat(fx.adminJson(mockMvc, base + "/quote", Map.of("amount", "35.00")).getResponse().getStatus()).isEqualTo(200);
        assertThat(fx.adminPlain(mockMvc, base + "/request-payment").getResponse().getStatus()).isEqualTo(200);
        assertThat(fx.adminPlain(mockMvc, base + "/confirm-payment").getResponse().getStatus()).isEqualTo(200);

        String text2 = pdfText(customerInvoice(owner, orderId).getResponse().getContentAsByteArray());
        assertThat(text2).isEqualTo(text1);
        assertThat(text1)
                .contains("INVOICE")
                .doesNotContain("REFUNDED")
                .contains("Invoice number: " + order.getOrderNumber())
                .contains("Week8 Card " + fx.ts + " (Box)")
                .contains("Subtotal: $600.00")
                .contains("Shipping: $0.00")
                .contains("Shipping billed separately")
                .contains("Tax: $0.00")
                .contains("Total: $600.00")
                .contains("Paid via MANUAL")
                .doesNotContain("$35.00")
                .doesNotContain("$635.00");
        assertThat(order.getSubtotal()).isEqualByComparingTo("600.00");
        assertThat(order.getTotal()).isEqualByComparingTo("600.00");

        byte[] attached = MailMessages.attachment(paymentConfirmedMessage(order.getOrderNumber()),
                "invoice-" + order.getOrderNumber() + ".pdf");
        assertThat(pdfText(attached)).isEqualTo(text1);
    }

    // ── 11. refunded invoice keeps amounts and is marked ──────────────

    @Test
    void refundedOrder_invoiceMarkedRefunded_amountsUnchanged() throws Exception {
        long orderId = fx.paidGuestOrder(mockMvc, "MA", 1);
        Order order = fx.order(orderId);
        MvcResult submit = fx.adminJson(mockMvc, "/admin/api/orders/" + orderId + "/refund-requests",
                Map.of("amount", order.getTotal(), "reason", "test"));
        long rrId = objectMapper.readTree(submit.getResponse().getContentAsString()).get("id").asLong();
        assertThat(fx.adminPlain(mockMvc, "/admin/api/refund-requests/" + rrId + "/approve")
                .getResponse().getStatus()).isEqualTo(200);

        MvcResult r = adminInvoice(orderId);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String text = pdfText(r.getResponse().getContentAsByteArray());
        assertThat(text)
                .contains("INVOICE")
                .contains("REFUNDED")
                .contains("Refunded on")
                .contains("Total: $600.00")
                .doesNotContain("Paid via");
    }

    // ── 12. authorization before availability ─────────────────────────

    @Test
    void authorization_ownerOtherAnonymousAdmin_noStateLeak() throws Exception {
        long paidId = paidCustomerOrder(owner, "MA");
        long pendingId = fx.customerCheckout(mockMvc, owner, "MA", 1);

        assertThat(customerInvoice(owner, paidId).getResponse().getStatus()).isEqualTo(200);
        MvcResult pendingOwner = customerInvoice(owner, pendingId);
        assertThat(pendingOwner.getResponse().getStatus()).isEqualTo(409);
        assertThat(pendingOwner.getResponse().getContentAsString()).contains("No invoice is available");

        MvcResult otherPaid = customerInvoice(other, paidId);
        MvcResult otherPending = customerInvoice(other, pendingId);
        assertThat(otherPaid.getResponse().getStatus()).isEqualTo(403);
        assertThat(otherPending.getResponse().getStatus()).isEqualTo(403);
        assertThat(otherPaid.getResponse().getContentAsString()).isEqualTo(otherPending.getResponse().getContentAsString());
        assertThat(otherPaid.getResponse().getContentAsString()).isEmpty();

        MvcResult anonymous = mockMvc.perform(get("/account/orders/" + paidId + "/invoice.pdf")).andReturn();
        assertThat(anonymous.getResponse().getStatus()).isBetween(300, 399);
        assertThat(anonymous.getResponse().getRedirectedUrl()).contains("/login");

        assertThat(adminInvoice(paidId).getResponse().getStatus()).isEqualTo(200);
        assertThat(adminInvoice(pendingId).getResponse().getStatus()).isEqualTo(409);
    }

    // ── 13. PDF content, headers, rendering ───────────────────────────

    @Test
    void invoicePreservesChineseAndLatinNamesInDownloadAndAttachment() throws Exception {
        Product product = fx.variant.getProduct();
        product.setName("Nikola Jokić 李雷");
        productRepository.save(product);
        String token = fx.newGuestToken();
        Cart cart = cartRepository.save(Cart.builder().sessionToken(token).build());
        cartService.addToCart(cart, fx.variant.getId(), 1);
        CheckoutRequest request = fx.guestRequest("MA", UUID.randomUUID().toString());
        request.setShippingFirstName("Łukasz");
        request.setShippingLastName("李雷");
        request.setShippingAddressLine1("100 Main St 李雷");
        MvcResult checkout = mockMvc.perform(post("/api/checkout").with(csrf())
                .cookie(new MockCookie("cart_token", token)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))).andReturn();
        assertThat(checkout.getResponse().getStatus()).isEqualTo(200);
        long orderId = objectMapper.readTree(checkout.getResponse().getContentAsString()).get("id").asLong();
        assertThat(fx.manualConfirm(mockMvc, orderId, "SUCCESS", UUID.randomUUID().toString())
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(fx.awaitSettled(orderId, PAYMENT_CONFIRMED).getStatus()).isEqualTo(NotificationStatus.SENT);

        MvcResult download = adminInvoice(orderId);
        assertThat(download.getResponse().getStatus()).isEqualTo(200);
        String text = pdfText(download.getResponse().getContentAsByteArray());
        assertThat(text).contains("Łukasz 李雷", "100 Main St 李雷", "Nikola Jokić 李雷", "Total: $600.00");
        Order order = fx.order(orderId);
        byte[] attached = MailMessages.attachment(paymentConfirmedMessage(order.getOrderNumber()),
                "invoice-" + order.getOrderNumber() + ".pdf");
        assertThat(pdfText(attached)).isEqualTo(text);
    }

    @Test
    void pdfContentAndHeaders() throws Exception {
        long orderId = paidCustomerOrder(owner, "MA");
        Order order = fx.order(orderId);
        MvcResult r = customerInvoice(owner, orderId);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(r.getResponse().getHeader("Content-Disposition"))
                .contains("attachment").contains("invoice-" + order.getOrderNumber() + ".pdf");
        byte[] bytes = r.getResponse().getContentAsByteArray();
        assertThat(new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");

        String text = pdfText(bytes);
        // Long SKUs wrap inside the narrow table column, so compare the SKU on whitespace-collapsed text.
        assertThat(text.replaceAll("\\s+", "")).contains("W8-SKU-" + fx.ts);
        assertThat(text)
                .contains(order.getOrderNumber())
                .contains("Week8 Card " + fx.ts + " (Box)")
                .contains("Subtotal: $600.00")
                .contains("Shipping: $0.00")
                .contains("Tax: $0.00")
                .contains("Total: $600.00")
                .contains("Test Merchant")
                .contains("Test Merchant LLC (test data)")
                .contains("Pat Recipient")
                .contains("Boston, MA 02101")
                .contains(" ET");
        assertThat(text).doesNotContain(order.getIdempotencyKey()).doesNotContain(order.getRequestFingerprint());
    }
}
