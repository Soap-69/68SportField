package com.cardshowcase;

import com.cardshowcase.model.entity.NotificationLog;
import com.cardshowcase.model.entity.OrderStatus;
import com.cardshowcase.model.entity.PaymentStatus;
import com.cardshowcase.repository.PaymentRepository;
import com.cardshowcase.service.InvoiceService;
import com.cardshowcase.support.RecordingMailSender;
import com.cardshowcase.support.Week8Fixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;

import static com.cardshowcase.model.entity.NotificationStatus.FAILED;
import static com.cardshowcase.model.entity.NotificationStatus.SENT;
import static com.cardshowcase.model.entity.NotificationType.ADMIN_NEW_PAID_ORDER;
import static com.cardshowcase.model.entity.NotificationType.PAYMENT_CONFIRMED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/** PDF generation failure after commit: business state intact, row FAILED (matrix item 4). */
class NotificationPdfFailureTest extends BaseIntegrationTest {

    @Autowired Week8Fixture fx;
    @Autowired JavaMailSender mailSender;
    @Autowired PaymentRepository paymentRepository;
    @MockitoSpyBean InvoiceService invoiceService;

    @BeforeEach
    void setUp() {
        fx.prepare(new BigDecimal("600.00"), 5);
        ((RecordingMailSender) mailSender).reset();
    }

    @Test
    void pdfFailure_paymentConfirmedFailed_adminEmailStillSent_businessStateCommitted() throws Exception {
        doThrow(new IllegalStateException("PDF boom")).when(invoiceService).render(anyLong());

        long orderId = fx.paidGuestOrder(mockMvc, "MA", 1);
        assertThat(fx.order(orderId).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(paymentRepository.findByOrder_Id(orderId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        NotificationLog confirmed = fx.awaitSettled(orderId, PAYMENT_CONFIRMED);
        assertThat(confirmed.getStatus()).isEqualTo(FAILED);
        assertThat(confirmed.getAttempts()).isEqualTo(1);
        assertThat(confirmed.getLastError()).contains("PDF boom");

        NotificationLog admin = fx.awaitSettled(orderId, ADMIN_NEW_PAID_ORDER);
        assertThat(admin.getStatus()).isEqualTo(SENT);
    }
}
