package com.cardshowcase.notification;

/** Everything the dispatcher needs to send one message; built inside the renderer's own transaction. */
public record RenderedNotification(String to, String subject, String html,
                                   String attachmentName, byte[] attachment) {
    public boolean hasAttachment() { return attachment != null; }
}
