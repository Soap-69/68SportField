package com.cardshowcase.security;

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

/** Contract tests for the forgot-password request budget (§4.9). */
class ForgotPasswordBudgetTest {

    private static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");
    private MutableClock clock;
    private ForgotPasswordBudget budget;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        budget = new ForgotPasswordBudget(clock);
    }

    private static String key(String s) {
        return IdentityDigest.customerKey(s);
    }

    @Test
    void threeAcceptedThenRejected_windowAnchoredAtFirstAcceptedRequest() {
        String k = key("a@x.test");
        clock.set(T0);
        assertThat(budget.tryAccept(k)).isTrue();
        clock.set(T0.plus(Duration.ofMinutes(59)));
        assertThat(budget.tryAccept(k)).isTrue();
        assertThat(budget.tryAccept(k)).isTrue();
        assertThat(budget.tryAccept(k)).isFalse();
        assertThat(budget.accepted(k)).isEqualTo(3);
        clock.set(T0.plus(Duration.ofMinutes(60)).minusMillis(1));
        assertThat(budget.tryAccept(k)).isFalse();
        clock.set(T0.plus(Duration.ofMinutes(60)));            // exact expiry: new window
        assertThat(budget.tryAccept(k)).isTrue();
        assertThat(budget.accepted(k)).isEqualTo(1);
    }

    @Test
    void concurrentBurstAcceptsExactlyThree() throws Exception {
        String k = key("b@x.test");
        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    if (budget.tryAccept(k)) accepted.incrementAndGet();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(accepted.get()).isEqualTo(3);
    }

    @Test
    void capacityIsAHardBound_andLiveBudgetsAreNotEvicted() {
        String exhausted = key("exhausted@x.test");
        for (int i = 0; i < 3; i++) assertThat(budget.tryAccept(exhausted)).isTrue();
        for (int i = 0; i < ForgotPasswordBudget.MAX_ENTRIES - 1; i++) {
            assertThat(budget.tryAccept(key("live-" + i + "-" + "z".repeat(400)))).isTrue();
        }
        assertThat(budget.size()).isEqualTo(ForgotPasswordBudget.MAX_ENTRIES);
        assertThat(budget.tryAccept(key("new@x.test"))).isFalse();          // saturated: rejected, no entry
        assertThat(budget.size()).isEqualTo(ForgotPasswordBudget.MAX_ENTRIES);
        assertThat(budget.accepted(key("new@x.test"))).isNull();
        assertThat(budget.tryAccept(exhausted)).isFalse();                  // still exhausted, not evicted
        assertThat(budget.tryAccept(key("live-0-" + "z".repeat(400)))).isTrue(); // existing key keeps its budget

        clock.set(T0.plus(Duration.ofMinutes(60)));
        assertThat(budget.tryAccept(key("new@x.test"))).isTrue();           // sweep freed capacity
        assertThat(budget.size()).isEqualTo(1);
    }

    @Test
    void concurrentAdmissionNeverExceedsCapacity() throws Exception {
        for (int i = 0; i < ForgotPasswordBudget.MAX_ENTRIES - 100; i++) {
            budget.tryAccept(key("pre-" + i));
        }
        int threads = 16, perThread = 40; // 640 candidates for 100 slots
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                int tt = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (budget.tryAccept(key("c-" + tt + "-" + i))) accepted.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(accepted.get()).isEqualTo(100);
        assertThat(budget.size()).isEqualTo(ForgotPasswordBudget.MAX_ENTRIES);
    }
}
