package com.cardshowcase.service;

import com.cardshowcase.model.entity.Inquiry;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;

@Service
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String fromEmail;

    @Value("${app.notification.admin-email:admin@cardshowcase.com}")
    private String adminEmail;

    @Value("${app.notification.enabled:true}")
    private boolean notificationsEnabled;

    /**
     * JavaMailSender is injected as required=false so the app starts and runs
     * without error when SMTP credentials are not configured. Any attempt to
     * send will be caught and logged as a warning.
     */
    public EmailService(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Sends a new-inquiry notification to the admin email.
     * Runs asynchronously — inquiry save is never blocked or failed by this.
     */
    @Async
    public void sendNewInquiryNotification(Inquiry inquiry) {
        if (!notificationsEnabled) {
            log.debug("Email notifications disabled — skipping notification for inquiry {}", inquiry.getId());
            return;
        }
        if (mailSender == null) {
            log.warn("Email notification not sent: JavaMailSender not configured (check MAIL_USERNAME / MAIL_PASSWORD)");
            return;
        }

        try {
            String subject = inquiry.getProduct() != null
                    ? "New Inquiry #" + inquiry.getId() + ": " + inquiry.getProduct().getName()
                    : "New Inquiry #" + inquiry.getId() + ": General Quote Request";

            String html = buildNotificationHtml(inquiry);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(adminEmail);
            helper.setSubject(subject);
            helper.setText(html, true);

            mailSender.send(message);
            log.info("Inquiry notification sent to {} for inquiry id={}", adminEmail, inquiry.getId());

        } catch (Exception ex) {
            log.warn("Email notification not sent for inquiry id={}: {}", inquiry.getId(), ex.getMessage());
        }
    }

    /**
     * Stub for future customer status-update emails.
     * Sends an email to the customer when their inquiry status changes.
     */
    @Async
    public void sendInquiryStatusUpdate(Inquiry inquiry) {
        // TODO: implement customer notification when status changes to REPLIED/CLOSED
        log.debug("sendInquiryStatusUpdate not yet implemented for inquiry id={}", inquiry.getId());
    }

    // ── HTML builder ──────────────────────────────────────────────

    private String buildNotificationHtml(Inquiry inquiry) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a");
        String timestamp = inquiry.getCreatedAt() != null
                ? inquiry.getCreatedAt().format(fmt)
                : "—";

        String productSection = "";
        if (inquiry.getProduct() != null) {
            String productName = escape(inquiry.getProduct().getName());
            String adminLink = "http://localhost:8080/admin/products/" + inquiry.getProduct().getId() + "/edit";
            productSection =
                "<tr>" +
                "  <td style='padding:8px 0;border-bottom:1px solid #f0f0f0;color:#6b7280;font-size:14px;width:140px;'>Product</td>" +
                "  <td style='padding:8px 0;border-bottom:1px solid #f0f0f0;font-size:14px;'>" +
                "    <a href='" + adminLink + "' style='color:#0071e3;text-decoration:none;'>" + productName + "</a>" +
                "  </td>" +
                "</tr>";
        }

        String inquiryAdminLink = "http://localhost:8080/admin/inquiries/" + inquiry.getId();

        return "<!DOCTYPE html>" +
            "<html lang='en'><head><meta charset='UTF-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>New Inquiry</title></head>" +
            "<body style='margin:0;padding:0;background:#f5f5f7;font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,sans-serif;'>" +

            "<table width='100%' cellpadding='0' cellspacing='0' style='background:#f5f5f7;'><tr><td align='center' style='padding:40px 16px;'>" +

            // Card
            "<table width='100%' cellpadding='0' cellspacing='0' style='max-width:600px;background:#ffffff;border-radius:12px;overflow:hidden;box-shadow:0 2px 12px rgba(0,0,0,.08);'>" +

            // Header
            "<tr><td style='background:#1d1d1f;padding:28px 32px;'>" +
            "  <p style='margin:0;font-size:20px;font-weight:700;color:#ffffff;letter-spacing:-.3px;'>68 Sport Field</p>" +
            "  <div style='margin-top:10px;height:3px;width:40px;background:#0071e3;border-radius:2px;'></div>" +
            "</td></tr>" +

            // Intro
            "<tr><td style='padding:28px 32px 20px;'>" +
            "  <p style='margin:0 0 4px;font-size:18px;font-weight:600;color:#1d1d1f;'>You have a new inquiry</p>" +
            "  <p style='margin:0;font-size:14px;color:#6b7280;'>Submitted " + timestamp + "</p>" +
            "</td></tr>" +

            // Details table
            "<tr><td style='padding:0 32px 28px;'>" +
            "  <table width='100%' cellpadding='0' cellspacing='0'>" +

            "    <tr>" +
            "      <td style='padding:8px 0;border-bottom:1px solid #f0f0f0;color:#6b7280;font-size:14px;width:140px;'>Inquiry ID</td>" +
            "      <td style='padding:8px 0;border-bottom:1px solid #f0f0f0;font-size:14px;font-weight:700;'>#" + inquiry.getId() + "</td>" +
            "    </tr>" +

            "    <tr>" +
            "      <td style='padding:8px 0;border-bottom:1px solid #f0f0f0;color:#6b7280;font-size:14px;width:140px;'>Name</td>" +
            "      <td style='padding:8px 0;border-bottom:1px solid #f0f0f0;font-size:14px;font-weight:500;'>" + escape(inquiry.getCustomerName()) + "</td>" +
            "    </tr>" +

            "    <tr>" +
            "      <td style='padding:8px 0;border-bottom:1px solid #f0f0f0;color:#6b7280;font-size:14px;'>Email</td>" +
            "      <td style='padding:8px 0;border-bottom:1px solid #f0f0f0;font-size:14px;'>" +
            "        <a href='mailto:" + escape(inquiry.getCustomerEmail()) + "' style='color:#0071e3;text-decoration:none;'>" + escape(inquiry.getCustomerEmail()) + "</a>" +
            "      </td>" +
            "    </tr>" +

            (inquiry.getCustomerPhone() != null && !inquiry.getCustomerPhone().isBlank() ?
            "    <tr>" +
            "      <td style='padding:8px 0;border-bottom:1px solid #f0f0f0;color:#6b7280;font-size:14px;'>Phone</td>" +
            "      <td style='padding:8px 0;border-bottom:1px solid #f0f0f0;font-size:14px;'>" + escape(inquiry.getCustomerPhone()) + "</td>" +
            "    </tr>" : "") +

            (inquiry.getCustomerCompany() != null && !inquiry.getCustomerCompany().isBlank() ?
            "    <tr>" +
            "      <td style='padding:8px 0;border-bottom:1px solid #f0f0f0;color:#6b7280;font-size:14px;'>Company</td>" +
            "      <td style='padding:8px 0;border-bottom:1px solid #f0f0f0;font-size:14px;'>" + escape(inquiry.getCustomerCompany()) + "</td>" +
            "    </tr>" : "") +

            productSection +

            (inquiry.getQuantity() != null ?
            "    <tr>" +
            "      <td style='padding:8px 0;border-bottom:1px solid #f0f0f0;color:#6b7280;font-size:14px;'>Quantity</td>" +
            "      <td style='padding:8px 0;border-bottom:1px solid #f0f0f0;font-size:14px;'>" + inquiry.getQuantity() + "</td>" +
            "    </tr>" : "") +

            (inquiry.getMessage() != null && !inquiry.getMessage().isBlank() ?
            "    <tr>" +
            "      <td style='padding:10px 0;color:#6b7280;font-size:14px;vertical-align:top;'>Message</td>" +
            "      <td style='padding:10px 0;font-size:14px;white-space:pre-wrap;'>" + escape(inquiry.getMessage()) + "</td>" +
            "    </tr>" : "") +

            "  </table>" +
            "</td></tr>" +

            // CTA button
            "<tr><td style='padding:0 32px 32px;'>" +
            "  <a href='" + inquiryAdminLink + "' " +
            "     style='display:inline-block;background:#0071e3;color:#ffffff;text-decoration:none;" +
            "            font-size:14px;font-weight:600;padding:12px 24px;border-radius:20px;'>" +
            "    View Inquiry in Admin Panel →" +
            "  </a>" +
            "</td></tr>" +

            // Footer
            "<tr><td style='padding:20px 32px;background:#f5f5f7;border-top:1px solid #e8e8e8;'>" +
            "  <p style='margin:0;font-size:12px;color:#9ca3af;text-align:center;'>" +
            "    This is an automated notification from the 68 Sport Field admin panel." +
            "  </p>" +
            "</td></tr>" +

            "</table>" + // end card
            "</td></tr></table>" + // end outer
            "</body></html>";
    }

    /** Basic HTML escaping to prevent XSS in email bodies. */
    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
