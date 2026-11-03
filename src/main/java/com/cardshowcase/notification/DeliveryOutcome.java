package com.cardshowcase.notification;

public enum DeliveryOutcome {
    /** This caller claimed the row and the mail server accepted the message. */
    SENT,
    /** This caller claimed the row; render, PDF or SMTP failed and was recorded. */
    FAILED,
    /** Notifications disabled or no mail sender; recorded, no delivery attempted. */
    SKIPPED_DISABLED,
    /** Another sender holds the row, or it is already SENT. Nothing was done. */
    NOT_CLAIMED
}
