package com.cardshowcase.support;

import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test double for JavaMailSender: records every MimeMessage instead of connecting
 * to an SMTP server, can be told to fail the next send, and can hold a send on a
 * latch so tests can observe the SENDING window. Never opens a socket.
 */
public class RecordingMailSender extends JavaMailSenderImpl {

    private final List<MimeMessage> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger failNext = new AtomicInteger(0);
    private volatile CountDownLatch gate;
    private volatile CountDownLatch entered;

    @Override
    public void send(MimeMessage mimeMessage) {
        CountDownLatch enteredNow = entered;
        if (enteredNow != null) {
            enteredNow.countDown();
        }
        CountDownLatch gateNow = gate;
        if (gateNow != null) {
            try {
                if (!gateNow.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("RecordingMailSender gate was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        if (failNext.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new MailSendException("Simulated SMTP failure");
        }
        sent.add(mimeMessage);
    }

    @Override
    public void send(MimeMessage... mimeMessages) {
        for (MimeMessage m : mimeMessages) {
            send(m);
        }
    }

    public List<MimeMessage> sent() { return sent; }

    public void reset() {
        sent.clear();
        failNext.set(0);
        gate = null;
        entered = null;
    }

    /** The next {@code count} sends throw MailSendException. */
    public void failNext(int count) { failNext.set(count); }

    /**
     * Holds every send until {@link #releaseGate()}; {@code entered} is counted down
     * when a sender reaches the gate so the test knows the row is in SENDING.
     */
    public CountDownLatch holdSends() {
        gate = new CountDownLatch(1);
        entered = new CountDownLatch(1);
        return entered;
    }

    public void releaseGate() {
        CountDownLatch g = gate;
        gate = null;
        if (g != null) {
            g.countDown();
        }
    }
}
