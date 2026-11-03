package com.cardshowcase.support;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Reads what tests need out of a recorded MimeMessage. */
public final class MailMessages {

    private MailMessages() {}

    public static String subject(MimeMessage m) {
        try { return m.getSubject(); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String firstRecipient(MimeMessage m) {
        try { return m.getAllRecipients()[0].toString(); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String replyTo(MimeMessage m) {
        try { return m.getReplyTo()[0].toString(); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String html(MimeMessage m) {
        try {
            m.saveChanges(); // recorded messages were never sent, so headers are not materialised yet
            String html = findHtml(m);
            if (html == null) throw new IllegalStateException("No text/html part in message " + m.getSubject());
            return html;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Names of all attachment parts (empty when the message is not multipart). */
    public static List<String> attachmentNames(MimeMessage m) {
        try {
            m.saveChanges();
            List<String> names = new ArrayList<>();
            collectAttachments(m, names, null, null);
            return names;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] attachment(MimeMessage m, String name) {
        try {
            m.saveChanges();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            boolean found = collectAttachments(m, new ArrayList<>(), name, out);
            if (!found) throw new IllegalStateException("No attachment named " + name);
            return out.toByteArray();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String findHtml(Part part) throws Exception {
        Object content = part.getContent();
        if (content instanceof String s && (part.isMimeType("text/html") || part.getContentType() == null)) {
            return s;
        }
        if (content instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                String found = findHtml(mp.getBodyPart(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean collectAttachments(Part part, List<String> names, String wanted,
                                              ByteArrayOutputStream out) throws Exception {
        Object content = part.getContent();
        boolean found = false;
        if (content instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                String fileName = bp.getFileName();
                if (fileName != null) {
                    names.add(fileName);
                    if (wanted != null && wanted.equals(fileName) && out != null) {
                        try (InputStream in = bp.getInputStream()) {
                            in.transferTo(out);
                        }
                        found = true;
                    }
                }
                found |= collectAttachments(bp, names, wanted, out);
            }
        }
        return found;
    }
}
