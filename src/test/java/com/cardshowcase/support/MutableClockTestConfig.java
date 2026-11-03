package com.cardshowcase.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Replaces the application {@code Clock} so throttle tests can move time deterministically. */
@TestConfiguration
public class MutableClockTestConfig {

    public static final Instant START = Instant.parse("2026-11-03T12:00:00Z");

    public static class MutableClock extends Clock {
        private volatile Instant instant;

        public MutableClock(Instant start) {
            this.instant = start;
        }

        public void set(Instant instant) {
            this.instant = instant;
        }

        public void advance(Duration d) {
            this.instant = this.instant.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(START);
    }
}
