package com.cardshowcase;

import com.cardshowcase.model.entity.NotificationLog;
import com.cardshowcase.support.MailMessages;
import com.cardshowcase.support.RecordingMailSender;
import com.cardshowcase.support.Week8Fixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;

import static com.cardshowcase.model.entity.NotificationStatus.SKIPPED_DISABLED;
import static com.cardshowcase.model.entity.NotificationType.ORDER_RECEIVED;
import static org.assertj.core.api.Assertions.assertThat;

/** Disabled path: rows are recorded as SKIPPED_DISABLED, no delivery, no attempts (matrix item 4). */
@TestPropertySource(properties = "app.notification.enabled=false")
class NotificationDisabledIntegrationTest extends BaseIntegrationTest {

    @Autowired Week8Fixture fx;
    @Autowired JavaMailSender mailSender;

    RecordingMailSender mail;

    @BeforeEach
    void setUp() {
        fx.prepare(new BigDecimal("600.00"), 5);
        mail = (RecordingMailSender) mailSender;
        mail.reset();
    }

    @Test
    void disabled_rowSkipped_zeroAttempts_resendKeepsItSkipped_noMessages() throws Exception {
        long orderId = fx.guestCheckout(mockMvc, "MA", 1, UUID.randomUUID().toString());
        NotificationLog row = fx.awaitSettled(orderId, ORDER_RECEIVED);
        assertThat(row.getStatus()).isEqualTo(SKIPPED_DISABLED);
        assertThat(row.getAttempts()).isZero();

        for (int i = 0; i < 2; i++) {
            MvcResult r = fx.adminPlain(mockMvc, "/admin/api/orders/" + orderId + "/notifications/ORDER_RECEIVED/resend");
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(r.getResponse().getContentAsString()).contains("SKIPPED_DISABLED");
        }
        NotificationLog after = fx.log(orderId, ORDER_RECEIVED);
        assertThat(after.getStatus()).isEqualTo(SKIPPED_DISABLED);
        assertThat(after.getAttempts()).isZero();

        String number = fx.order(orderId).getOrderNumber();
        assertThat(mail.sent().stream().filter(m -> MailMessages.subject(m).contains(number))).isEmpty();
    }
}
