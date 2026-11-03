package com.cardshowcase.security;

import com.cardshowcase.support.MutableClockTestConfig.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reservation settles exactly once on every delegate exit (§4.2 lifecycle). */
class ThrottlingAuthenticationProviderTest {

    private final AtomicInteger delegateCalls = new AtomicInteger();
    private LoginAttemptLimiter limiter;
    private Supplier<Object> outcome; // returns Authentication, or throws

    private final AuthenticationProvider delegate = new AuthenticationProvider() {
        @Override
        public Authentication authenticate(Authentication authentication) throws AuthenticationException {
            delegateCalls.incrementAndGet();
            return (Authentication) outcome.get();
        }

        @Override
        public boolean supports(Class<?> authentication) {
            return true;
        }
    };

    private ThrottlingAuthenticationProvider provider;
    private static final String USER = "user@x.test";
    private static final String KEY = IdentityDigest.customerKey(USER);

    @BeforeEach
    void setUp() {
        limiter = new LoginAttemptLimiter(Realm.CUSTOMER, new MutableClock(Instant.parse("2026-11-03T10:00:00Z")));
        provider = new ThrottlingAuthenticationProvider(delegate, Realm.CUSTOMER, limiter);
    }

    private static Authentication request() {
        return UsernamePasswordAuthenticationToken.unauthenticated(USER, "pw");
    }

    @Test
    void successClearsState() {
        outcome = () -> UsernamePasswordAuthenticationToken.authenticated(USER, null, java.util.List.of());
        assertThat(provider.authenticate(request())).isNotNull();
        assertThat(delegateCalls.get()).isEqualTo(1);
        assertThat(limiter.snapshot(KEY)).isNull();
    }

    @Test
    void credentialAndAccountFailuresCount_otherFailuresRelease() {
        outcome = () -> { throw new BadCredentialsException("bad"); };
        assertThatThrownBy(() -> provider.authenticate(request())).isInstanceOf(BadCredentialsException.class);
        assertThat(limiter.snapshot(KEY).failures()).isEqualTo(1);

        outcome = () -> { throw new DisabledException("disabled"); };
        assertThatThrownBy(() -> provider.authenticate(request())).isInstanceOf(DisabledException.class);
        assertThat(limiter.snapshot(KEY).failures()).isEqualTo(2);

        outcome = () -> { throw new InternalAuthenticationServiceException("db down"); };
        assertThatThrownBy(() -> provider.authenticate(request()))
                .isInstanceOf(InternalAuthenticationServiceException.class).hasMessage("db down");
        assertThat(limiter.snapshot(KEY).failures()).isEqualTo(2);
        assertThat(limiter.snapshot(KEY).inFlight()).isZero();

        RuntimeException boom = new IllegalStateException("boom");
        outcome = () -> { throw boom; };
        assertThatThrownBy(() -> provider.authenticate(request())).isSameAs(boom);
        assertThat(limiter.snapshot(KEY).failures()).isEqualTo(2);
        assertThat(limiter.snapshot(KEY).inFlight()).isZero();

        outcome = () -> null;
        assertThat(provider.authenticate(request())).isNull();
        assertThat(limiter.snapshot(KEY).failures()).isEqualTo(2);
        assertThat(limiter.snapshot(KEY).inFlight()).isZero();
        assertThat(delegateCalls.get()).isEqualTo(5);
    }

    @Test
    void refusedRequestsNeverReachTheDelegate_andReleaseNothing() {
        outcome = () -> { throw new BadCredentialsException("bad"); };
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> provider.authenticate(request())).isInstanceOf(BadCredentialsException.class);
        }
        assertThat(delegateCalls.get()).isEqualTo(5);
        assertThat(limiter.snapshot(KEY).blockedUntil()).isNotNull();
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> provider.authenticate(request())).isInstanceOf(LockedException.class);
        }
        assertThat(delegateCalls.get()).isEqualTo(5);
        assertThat(limiter.snapshot(KEY).failures()).isEqualTo(5);
        assertThat(limiter.snapshot(KEY).inFlight()).isZero();
    }

    @Test
    void customerKeyIsCaseFolded_adminKeyIsNot() {
        outcome = () -> { throw new BadCredentialsException("bad"); };
        assertThatThrownBy(() -> provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("USER@X.TEST", "pw")))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(limiter.snapshot(KEY).failures()).isEqualTo(1);

        LoginAttemptLimiter adminLimiter = new LoginAttemptLimiter(Realm.ADMIN, new MutableClock(Instant.parse("2026-11-03T10:00:00Z")));
        ThrottlingAuthenticationProvider adminProvider = new ThrottlingAuthenticationProvider(delegate, Realm.ADMIN, adminLimiter);
        assertThatThrownBy(() -> adminProvider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("Admin", "pw")))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(adminLimiter.snapshot(IdentityDigest.adminKey("Admin")).failures()).isEqualTo(1);
        assertThat(adminLimiter.snapshot(IdentityDigest.adminKey("admin"))).isNull();
    }
}
