package com.cardshowcase.notification;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Values the notification and invoice code reads from configuration.
 * Merchant identity keys have no defaults: blank means "not provided", and the
 * templates and the invoice omit the corresponding block.
 */
@Component
@Getter
public class NotificationSettings {

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${app.mail.reply-to}")
    private String replyTo;

    @Value("${spring.mail.username:}")
    private String fromEmail;

    @Value("${app.notification.admin-email:}")
    private String adminEmail;

    @Value("${app.notification.enabled:true}")
    private boolean enabled;

    @Value("${app.merchant.display-name:}")
    private String merchantDisplayName;

    @Value("${app.merchant.legal-name:}")
    private String merchantLegalName;

    @Value("${app.merchant.address:}")
    private String merchantAddress;

    public boolean hasMerchantDisplayName() { return !merchantDisplayName.isBlank(); }
    public boolean hasMerchantLegalName()   { return !merchantLegalName.isBlank(); }
    public boolean hasMerchantAddress()     { return !merchantAddress.isBlank(); }
}
