package com.cardshowcase.security;

import com.cardshowcase.security.LoginAttemptLimiter.Admission;
import com.cardshowcase.support.MutableClockTestConfig.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contract tests for the limiter state machine (§4.2, §4.5): admission reservation, expiry, capacity. */
class LoginAttemptLimiterTest {

    private static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");

    private MutableClock clock;
    private LoginAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        limiter = new LoginAttemptLimiter(Realm.CUSTOMER, clock);
    }

    private static String key(String s) {
        return IdentityDigest.customerKey(s);
    }

    private void fail(String k) {
        assertThat(limiter.admit(k)).isEqualTo(Admission.ADMITTED);
        limiter.recordFailure(k);
    }

    @Test
    void fifthFailureEstablishesLock_sixthRequestIsBlocked_withoutCounting() {
        String k = key("a@x.test");
        for (int i = 0; i < 4; i++) {
            fail(k);
            assertThat(limiter.snapshot(k).failures()).isEqualTo(i + 1);
            assertThat(limiter.snapshot(k).blockedUntil()).isNull();
        }
        fail(k); // fifth failure: admitted, counted, lock established from now
        assertThat(limiter.snapshot(k).failures()).isEqualTo(5);
        assertThat(limiter.snapshot(k).blockedUntil()).isEqualTo(T0.plus(LoginAttemptLimiter.LOCK));

        for (int i = 0; i < 50; i++) {
            assertThat(limiter.admit(k)).isEqualTo(Admission.BLOCKED);
        }
        assertThat(limiter.snapshot(k).failures()).isEqualTo(5);
        assertThat(limiter.snapshot(k).blockedUntil()).isEqualTo(T0.plus(LoginAttemptLimiter.LOCK));
        assertThat(limiter.snapshot(k).inFlight()).isZero();
    }

    @Test
    void admissionReservesBudget_fourFailuresAdmitExactlyOneMore() {
        String k = key("b@x.test");
        for (int i = 0; i < 4; i++) fail(k);
        assertThat(limiter.admit(k)).isEqualTo(Admission.ADMITTED);   // 4 + 1 in flight = 5
        for (int i = 0; i < 9; i++) {
            assertThat(limiter.admit(k)).isEqualTo(Admission.BLOCKED); // refused before delegation
        }
        assertThat(limiter.snapshot(k).inFlight()).isEqualTo(1);
        limiter.recordFailure(k);
        assertThat(limiter.snapshot(k).failures()).isEqualTo(5);
        assertThat(limiter.snapshot(k).blockedUntil()).isNotNull();
        assertThat(limiter.snapshot(k).inFlight()).isZero();
    }

    @Test
    void fromZero_aBurstAdmitsExactlyFive() {
        String k = key("c@x.test");
        int admitted = 0, blocked = 0;
        for (int i = 0; i < 20; i++) {
            if (limiter.admit(k) == Admission.ADMITTED) admitted++; else blocked++;
        }
        assertThat(admitted).isEqualTo(5);
        assertThat(blocked).isEqualTo(15);
        for (int i = 0; i < 5; i++) limiter.recordFailure(k);
        assertThat(limiter.snapshot(k).blockedUntil()).isNotNull();
        assertThat(limiter.admit(k)).isEqualTo(Admission.BLOCKED);
    }

    @Test
    void differentKeysProgressIndependently() {
        String a = key("d1@x.test"), b = key("d2@x.test");
        for (int i = 0; i < 5; i++) fail(a);
        assertThat(limiter.admit(a)).isEqualTo(Admission.BLOCKED);
        assertThat(limiter.admit(b)).isEqualTo(Admission.ADMITTED);
        limiter.recordSuccess(b);
        assertThat(limiter.snapshot(b)).isNull();
    }

    @Test
    void completionOrder_successThenFailure_andFailureThenSuccess() {
        String k = key("e@x.test");
        for (int i = 0; i < 3; i++) fail(k);
        // two admitted together (3 + 2 = 5), a third is refused
        assertThat(limiter.admit(k)).isEqualTo(Admission.ADMITTED);
        assertThat(limiter.admit(k)).isEqualTo(Admission.ADMITTED);
        assertThat(limiter.admit(k)).isEqualTo(Admission.BLOCKED);

        // wrong completes first, then correct → entry cleared and dropped
        limiter.recordFailure(k);
        assertThat(limiter.snapshot(k).failures()).isEqualTo(4);
        limiter.recordSuccess(k);
        assertThat(limiter.snapshot(k)).isNull();

        // correct first, then wrong → new window with one failure
        for (int i = 0; i < 3; i++) fail(k);
        assertThat(limiter.admit(k)).isEqualTo(Admission.ADMITTED);
        assertThat(limiter.admit(k)).isEqualTo(Admission.ADMITTED);
        clock.advance(Duration.ofSeconds(30));
        limiter.recordSuccess(k);
        assertThat(limiter.snapshot(k).failures()).isZero();
        assertThat(limiter.snapshot(k).inFlight()).isEqualTo(1);
        limiter.recordFailure(k);
        assertThat(limiter.snapshot(k).failures()).isEqualTo(1);
        assertThat(limiter.snapshot(k).windowStart()).isEqualTo(T0.plusSeconds(30));
        assertThat(limiter.snapshot(k).inFlight()).isZero();
    }

    @Test
    void lockOutlivesTheObservationWindow_andExpiresAtTheExactInstant() {
        String k = key("f@x.test");
        for (int i = 0; i < 4; i++) {
            clock.set(T0.plus(Duration.ofMinutes(i)));
            fail(k);
        }
        clock.set(T0.plus(Duration.ofMinutes(14)));
        fail(k); // fifth failure inside the window → lock until 29:00
        assertThat(limiter.snapshot(k).blockedUntil()).isEqualTo(T0.plus(Duration.ofMinutes(29)));

        clock.set(T0.plus(Duration.ofMinutes(15)));          // window end does not clear the lock
        assertThat(limiter.admit(k)).isEqualTo(Admission.BLOCKED);
        clock.set(T0.plus(Duration.ofMinutes(29)).minusMillis(1));
        assertThat(limiter.admit(k)).isEqualTo(Admission.BLOCKED);
        clock.set(T0.plus(Duration.ofMinutes(29)));          // exact expiry instant: admitted
        assertThat(limiter.admit(k)).isEqualTo(Admission.ADMITTED);
        limiter.recordFailure(k);
        assertThat(limiter.snapshot(k).failures()).isEqualTo(1);
        assertThat(limiter.snapshot(k).windowStart()).isEqualTo(T0.plus(Duration.ofMinutes(29)));
        assertThat(limiter.snapshot(k).blockedUntil()).isNull();
    }

    @Test
    void windowWithFewerThanFiveFailuresExpiresAtExactlyFifteenMinutes() {
        String k = key("g@x.test");
        for (int i = 0; i < 4; i++) {
            clock.set(T0.plus(Duration.ofSeconds(15L * i)));
            fail(k);
        }
        clock.set(T0.plus(Duration.ofMinutes(15)).minusMillis(1));
        fail(k); // still inside the window: fifth failure → lock
        assertThat(limiter.snapshot(k).blockedUntil()).isNotNull();

        String k2 = key("h@x.test");
        for (int i = 0; i < 4; i++) {
            clock.set(T0.plus(Duration.ofSeconds(15L * i)));
            fail(k2);
        }
        clock.set(T0.plus(Duration.ofMinutes(15)));
        fail(k2); // window ended at exactly 15:00 → this is the first failure of a new window
        assertThat(limiter.snapshot(k2).failures()).isEqualTo(1);
        assertThat(limiter.snapshot(k2).blockedUntil()).isNull();
    }

    @Test
    void releaseDoesNotCount_andSettlingTwiceIsRejected() {
        String k = key("i@x.test");
        assertThat(limiter.admit(k)).isEqualTo(Admission.ADMITTED);
        limiter.release(k);
        assertThat(limiter.snapshot(k)).isNull();
        assertThatThrownBy(() -> limiter.release(k)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> limiter.recordFailure(k)).isInstanceOf(IllegalStateException.class);

        fail(k);
        assertThat(limiter.admit(k)).isEqualTo(Admission.ADMITTED);
        limiter.release(k);
        assertThat(limiter.snapshot(k).failures()).isEqualTo(1);
        assertThat(limiter.snapshot(k).inFlight()).isZero();
        assertThatThrownBy(() -> limiter.release(k)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void outstandingReservationSurvivesWindowExpiryAndSweep() {
        String k = key("j@x.test");
        fail(k);
        fail(k);
        assertThat(limiter.admit(k)).isEqualTo(Admission.ADMITTED);      // held request
        clock.set(T0.plus(Duration.ofMinutes(16)));                        // window expired while in flight
        assertThat(limiter.admit(k)).isEqualTo(Admission.ADMITTED);      // expiry resets failures, keeps inFlight
        assertThat(limiter.snapshot(k).failures()).isZero();
        assertThat(limiter.snapshot(k).inFlight()).isEqualTo(2);
        limiter.release(k);
        // force a sweep at capacity: the in-flight entry must survive it
        for (int i = 0; i < LoginAttemptLimiter.MAX_ENTRIES - 1; i++) {
            String f = key("fill-" + i);
            assertThat(limiter.admit(f)).isEqualTo(Admission.ADMITTED);
            limiter.recordFailure(f);
        }
        assertThat(limiter.size()).isEqualTo(LoginAttemptLimiter.MAX_ENTRIES);
        clock.set(T0.plus(Duration.ofMinutes(40)));                        // everything expired except in-flight k
        assertThat(limiter.admit(key("new@x.test"))).isEqualTo(Admission.ADMITTED); // sweep ran
        assertThat(limiter.size()).isEqualTo(2);
        assertThat(limiter.snapshot(k).inFlight()).isEqualTo(1);
        limiter.recordFailure(k);
        assertThat(limiter.snapshot(k).failures()).isEqualTo(1);
        assertThat(limiter.snapshot(k).inFlight()).isZero();
    }

    @Test
    void capacityIsAHardBound_saturationRefusesNewKeys_andNeverEvictsLiveState() {
        String locked = key("locked@x.test");
        for (int i = 0; i < 5; i++) fail(locked);
        String counting = key("counting@x.test");
        fail(counting);
        for (int i = 0; i < LoginAttemptLimiter.MAX_ENTRIES - 2; i++) {
            String f = key("live-" + i + "-" + "y".repeat(300)); // long distinct identities, still 64-hex keys
            assertThat(f).hasSize(64);
            assertThat(limiter.admit(f)).isEqualTo(Admission.ADMITTED);
            limiter.recordFailure(f);
        }
        assertThat(limiter.size()).isEqualTo(LoginAttemptLimiter.MAX_ENTRIES);

        assertThat(limiter.admit(key("one-too-many@x.test"))).isEqualTo(Admission.SATURATED);
        assertThat(limiter.size()).isEqualTo(LoginAttemptLimiter.MAX_ENTRIES);
        assertThat(limiter.snapshot(key("one-too-many@x.test"))).isNull();
        assertThat(limiter.admit(locked)).isEqualTo(Admission.BLOCKED);                 // lock preserved
        assertThat(limiter.admit(counting)).isEqualTo(Admission.ADMITTED);              // existing key keeps its budget
        limiter.recordFailure(counting);
        assertThat(limiter.snapshot(counting).failures()).isEqualTo(2);

        clock.set(T0.plus(Duration.ofMinutes(31)));                                      // all windows/locks expired
        assertThat(limiter.admit(key("one-too-many@x.test"))).isEqualTo(Admission.ADMITTED);
        assertThat(limiter.size()).isEqualTo(1);
    }

    @Test
    void concurrentAdmissionNeverExceedsCapacity() throws Exception {
        for (int i = 0; i < LoginAttemptLimiter.MAX_ENTRIES - 200; i++) {
            String f = key("pre-" + i);
            limiter.admit(f);
            limiter.recordFailure(f);
        }
        int threads = 16, perThread = 50; // 800 candidates for 200 free slots
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                int tt = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        String f = key("conc-" + tt + "-" + i);
                        if (limiter.admit(f) == Admission.ADMITTED) {
                            admitted.incrementAndGet();
                            limiter.recordFailure(f);
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(admitted.get()).isEqualTo(200);
        assertThat(limiter.size()).isEqualTo(LoginAttemptLimiter.MAX_ENTRIES);
    }

    @Test
    void concurrentBurstOnOneKeyAdmitsExactlyFive() throws Exception {
        String k = key("burst@x.test");
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    if (limiter.admit(k) == Admission.ADMITTED) admitted.incrementAndGet();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(admitted.get()).isEqualTo(5);
        assertThat(limiter.snapshot(k).inFlight()).isEqualTo(5);
    }
}
