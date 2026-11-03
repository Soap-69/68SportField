package com.cardshowcase;

import com.cardshowcase.service.InvoiceService;
import com.cardshowcase.support.MailMessages;
import com.cardshowcase.support.RecordingMailSender;
import com.cardshowcase.support.Week8Fixture;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static com.cardshowcase.model.entity.NotificationType.ADMIN_NEW_PAID_ORDER;
import static com.cardshowcase.model.entity.NotificationType.ORDER_RECEIVED;
import static com.cardshowcase.model.entity.NotificationType.PAYMENT_CONFIRMED;
import static org.assertj.core.api.Assertions.assertThat;

/** Blank merchant configuration renders no merchant block anywhere (D8; matrix item 9). */
@TestPropertySource(properties = {
        "app.merchant.display-name=",
        "app.merchant.legal-name=",
        "app.merchant.address="
})
class MerchantBlankConfigTest extends BaseIntegrationTest {

    @Autowired Week8Fixture fx;
    @Autowired JavaMailSender mailSender;
    @Autowired InvoiceService invoiceService;

    RecordingMailSender mail;

    @BeforeEach
    void setUp() {
        fx.prepare(new BigDecimal("600.00"), 5);
        mail = (RecordingMailSender) mailSender;
        mail.reset();
    }

    @Test
    void blankMerchant_omittedFromEmailAndInvoice() throws Exception {
        long orderId = fx.paidGuestOrder(mockMvc, "MA", 1);
        fx.awaitSettled(orderId, ORDER_RECEIVED);
        fx.awaitSettled(orderId, PAYMENT_CONFIRMED);
        fx.awaitSettled(orderId, ADMIN_NEW_PAID_ORDER);
        String number = fx.order(orderId).getOrderNumber();

        List<MimeMessage> forOrder = mail.sent().stream()
                .filter(m -> MailMessages.subject(m).contains(number)).toList();
        assertThat(forOrder).hasSize(3);
        for (MimeMessage m : forOrder) {
            assertThat(MailMessages.html(m)).doesNotContain("Test Merchant");
        }
        List<MimeMessage> orderReceived = forOrder.stream()
                .filter(m -> MailMessages.subject(m).equals("Order " + number + " received"))
                .toList();
        assertThat(orderReceived).hasSize(1);
        assertThat(MailMessages.html(orderReceived.get(0))).contains("Order update");

        String text = InvoiceIntegrationTest.pdfText(invoiceService.render(orderId));
        assertThat(text).contains("INVOICE").doesNotContain("Test Merchant").doesNotContain("Test Street");
    }
}
