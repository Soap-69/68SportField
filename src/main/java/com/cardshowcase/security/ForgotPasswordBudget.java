package com.cardshowcase.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Request budget for POST /forgot-password: at most {@value #LIMIT} accepted requests per
 * lookup identity inside a 60-minute window anchored at the first accepted request,
 * independent of whether the account exists. Keys are 64-hex SHA-256 digests of the exact
 * identity the endpoint queries. At most {@value #MAX_ENTRIES} live keys; when full after an
 * expiry sweep, new keys are rejected (no token, no mail). Independent of the login limiter.
 */
@Slf4j
@Component
public class ForgotPasswordBudget {

    static final int LIMIT = 3;
    static final Duration WINDOW = Duration.ofMinutes(60);
    static final int MAX_ENTRIES = 10_000;

    private static final class Entry {
        int accepted;
        Instant windowStart;
    }

    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Entry> entries = new HashMap<>();
    private boolean saturationLogged;

    public ForgotPasswordBudget(Clock clock) {
        this.clock = clock;
    }

    /** @return true when this request is admitted (token/mail may proceed), false when rejected. */
    public boolean tryAccept(String key) {
        Instant now = clock.instant();
        lock.lock();
        try {
            Entry e = entries.get(key);
            if (e != null && !now.isBefore(e.windowStart.plus(WINDOW))) {
                entries.remove(key);
                e = null;
            }
            if (e == null) {
                if (entries.size() >= MAX_ENTRIES) {
                    sweep(now);
                }
                if (entries.size() >= MAX_ENTRIES) {
                    if (!saturationLogged) {
                        log.warn("Forgot-password budget saturated at {} live entries; rejecting new identities until capacity frees",
                                entries.size());
                        saturationLogged = true;
                    }
                    return false;
                }
                e = new Entry();
                e.windowStart = now;
                entries.put(key, e);
            }
            if (e.accepted >= LIMIT) {
                return false;
            }
            e.accepted++;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Accepted count for the key's current window, or null when no live entry exists. */
    public Integer accepted(String key) {
        lock.lock();
        try {
            Entry e = entries.get(key);
            return e == null ? null : e.accepted;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }

    private void sweep(Instant now) {
        Iterator<Entry> it = entries.values().iterator();
        while (it.hasNext()) {
            if (!now.isBefore(it.next().windowStart.plus(WINDOW))) {
                it.remove();
            }
        }
        if (entries.size() < MAX_ENTRIES) {
            saturationLogged = false;
        }
    }
}
