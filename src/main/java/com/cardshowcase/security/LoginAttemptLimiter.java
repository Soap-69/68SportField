package com.cardshowcase.security;

import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-realm login attempt budget. Five credential failures inside a 15-minute window anchored
 * at the first failure lock the key for 15 minutes from the fifth failure. Admission reserves
 * budget ({@code failures + inFlight < 5}), so a concurrent burst cannot exceed the threshold.
 * Keys are 64-hex SHA-256 digests (see {@link IdentityDigest}); the map holds at most
 * {@value #MAX_ENTRIES} live keys and never evicts live state: when it is full after an
 * expiry sweep, new keys are refused (fail-closed). All bookkeeping runs under one short
 * instance lock that is never held across the delegate's authentication work.
 */
@Slf4j
public class LoginAttemptLimiter {

    public enum Admission { ADMITTED, BLOCKED, SATURATED }

    /** Read-only view for tests and diagnostics. */
    public record Snapshot(int failures, Instant windowStart, Instant blockedUntil, int inFlight) {}

    static final int THRESHOLD = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration LOCK = Duration.ofMinutes(15);
    static final int MAX_ENTRIES = 10_000;

    private static final class Entry {
        int failures;
        Instant windowStart;
        Instant blockedUntil;
        int inFlight;

        boolean empty() {
            return failures == 0 && inFlight == 0 && blockedUntil == null;
        }
    }

    private final Realm realm;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Entry> entries = new HashMap<>();
    private boolean saturationLogged;

    public LoginAttemptLimiter(Realm realm, Clock clock) {
        this.realm = realm;
        this.clock = clock;
    }

    public Admission admit(String key) {
        Instant now = clock.instant();
        lock.lock();
        try {
            Entry e = entries.get(key);
            if (e != null) {
                expire(e, now);
                if (e.empty()) {
                    entries.remove(key);
                    e = null;
                }
            }
            if (e != null) {
                if (e.blockedUntil != null) {
                    return Admission.BLOCKED;
                }
                if (e.failures + e.inFlight >= THRESHOLD) {
                    return Admission.BLOCKED;
                }
            } else {
                if (entries.size() >= MAX_ENTRIES) {
                    sweep(now);
                }
                if (entries.size() >= MAX_ENTRIES) {
                    if (!saturationLogged) {
                        log.warn("Login limiter {} saturated at {} live entries; refusing new identities until capacity frees",
                                realm, entries.size());
                        saturationLogged = true;
                    }
                    return Admission.SATURATED;
                }
                e = new Entry();
                entries.put(key, e);
            }
            e.inFlight++;
            return Admission.ADMITTED;
        } finally {
            lock.unlock();
        }
    }

    /** Credential or account failure reported by the delegate for an admitted request. */
    public void recordFailure(String key) {
        Instant now = clock.instant();
        lock.lock();
        try {
            Entry e = reserved(key);
            expire(e, now);
            e.inFlight--;
            if (e.windowStart == null) {
                e.windowStart = now;
                e.failures = 1;
            } else {
                e.failures++;
            }
            if (e.failures >= THRESHOLD && e.blockedUntil == null) {
                e.blockedUntil = now.plus(LOCK);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Successful authentication of an admitted request: clears the key's failure state. */
    public void recordSuccess(String key) {
        lock.lock();
        try {
            Entry e = reserved(key);
            e.inFlight--;
            e.failures = 0;
            e.windowStart = null;
            e.blockedUntil = null;
            dropIfEmpty(key, e);
        } finally {
            lock.unlock();
        }
    }

    /** Admitted request ended without a countable outcome: nothing is counted. */
    public void release(String key) {
        lock.lock();
        try {
            Entry e = reserved(key);
            e.inFlight--;
            dropIfEmpty(key, e);
        } finally {
            lock.unlock();
        }
    }

    public Snapshot snapshot(String key) {
        lock.lock();
        try {
            Entry e = entries.get(key);
            return e == null ? null : new Snapshot(e.failures, e.windowStart, e.blockedUntil, e.inFlight);
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

    private Entry reserved(String key) {
        Entry e = entries.get(key);
        if (e == null || e.inFlight <= 0) {
            throw new IllegalStateException("No admitted reservation to settle");
        }
        return e;
    }

    /** An active lock outlives the observation window; the window only resets while no lock is set. */
    private static void expire(Entry e, Instant now) {
        if (e.blockedUntil != null) {
            if (!now.isBefore(e.blockedUntil)) {
                e.failures = 0;
                e.windowStart = null;
                e.blockedUntil = null;
            }
        } else if (e.windowStart != null && !now.isBefore(e.windowStart.plus(WINDOW))) {
            e.failures = 0;
            e.windowStart = null;
        }
    }

    private void sweep(Instant now) {
        Iterator<Entry> it = entries.values().iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            expire(e, now);
            if (e.empty()) {
                it.remove();
            }
        }
        if (entries.size() < MAX_ENTRIES) {
            saturationLogged = false;
        }
    }

    private void dropIfEmpty(String key, Entry e) {
        if (e.empty()) {
            entries.remove(key);
            if (entries.size() < MAX_ENTRIES) {
                saturationLogged = false;
            }
        }
    }
}
