package com.cardshowcase.model.entity;

public enum NotificationStatus {
    /** Inserted with the business transaction; no delivery attempted yet. */
    PENDING,
    /** Claimed by exactly one sender; delivery in progress. Not claimable. */
    SENDING,
    /** Mail server accepted the message. Terminal; never resent. */
    SENT,
    /** Render, PDF or SMTP failure recorded in last_error. Claimable by Resend. */
    FAILED,
    /** Notifications disabled or no mail sender configured. Claimable by Resend. */
    SKIPPED_DISABLED
}
