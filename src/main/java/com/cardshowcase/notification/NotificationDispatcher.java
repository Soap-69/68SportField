package com.cardshowcase.notification;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The single delivery entry for automatic sends and manual resends.
 *
 * Guarantee: at most one delivery attempt per claim; idempotent business replays never
 * create a second notification record. If the mail server accepted a message but the
 * process failed before the record was marked SENT, a manual resend can deliver that
 * message a second time. Exactly-once end-to-end delivery is not guaranteed.
 *
 * Everything here runs after the business transaction has committed. Failures are
 * recorded on the row in their own transaction and never reach a business caller.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatcher {

    private final NotificationLogWriter writer;
    private final NotificationRenderer renderer;
    private final NotificationSettings settings;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEnqueued(NotificationEnqueuedEvent event) {
        deliver(event.logId());
    }

    public DeliveryOutcome deliver(long logId) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (!settings.isEnabled() || sender == null) {
            return writer.markSkippedDisabled(logId)
                    ? DeliveryOutcome.SKIPPED_DISABLED : DeliveryOutcome.NOT_CLAIMED;
        }
        if (!writer.claim(logId)) {
            return DeliveryOutcome.NOT_CLAIMED;
        }
        try {
            RenderedNotification r = renderer.render(logId);
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, r.hasAttachment(), "UTF-8");
            helper.setFrom(settings.getFromEmail());
            helper.setTo(r.to());
            helper.setReplyTo(settings.getReplyTo());
            helper.setSubject(r.subject());
            helper.setText(r.html(), true);
            if (r.hasAttachment()) {
                helper.addAttachment(r.attachmentName(), new ByteArrayResource(r.attachment()), "application/pdf");
            }
            sender.send(message);
            writer.markSent(logId);
            return DeliveryOutcome.SENT;
        } catch (Exception e) {
            log.warn("Notification {} delivery failed: {}", logId, e.toString());
            writer.markFailed(logId, e.toString());
            return DeliveryOutcome.FAILED;
        }
    }
}
