package com.cardshowcase.notification;

/** Published inside the business transaction; carries only the row id. */
public record NotificationEnqueuedEvent(long logId) {}
