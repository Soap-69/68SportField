package com.cardshowcase.security;

import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

/**
 * Decorates one realm's DAO provider with the attempt budget. A refused request throws
 * {@link LockedException} before any user lookup or BCrypt work. An admitted request settles
 * its reservation exactly once: success clears the key, a credential/account failure counts,
 * every other exit (service failure, unchecked exception, null result) releases uncounted
 * and propagates unchanged.
 */
public class ThrottlingAuthenticationProvider implements AuthenticationProvider {

    private final AuthenticationProvider delegate;
    private final Realm realm;
    private final LoginAttemptLimiter limiter;

    public ThrottlingAuthenticationProvider(AuthenticationProvider delegate, Realm realm, LoginAttemptLimiter limiter) {
        this.delegate = delegate;
        this.realm = realm;
        this.limiter = limiter;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String name = authentication.getName() == null ? "" : authentication.getName();
        String key = realm == Realm.ADMIN ? IdentityDigest.adminKey(name) : IdentityDigest.customerKey(CustomerEmail.normalize(name));

        if (limiter.admit(key) != LoginAttemptLimiter.Admission.ADMITTED) {
            throw new LockedException("Too many sign-in attempts");
        }

        boolean settled = false;
        try {
            Authentication result = delegate.authenticate(authentication);
            if (result == null) {
                limiter.release(key);
                settled = true;
                return null;
            }
            limiter.recordSuccess(key);
            settled = true;
            return result;
        } catch (BadCredentialsException | AccountStatusException e) {
            limiter.recordFailure(key);
            settled = true;
            throw e;
        } finally {
            if (!settled) {
                limiter.release(key);
            }
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return delegate.supports(authentication);
    }
}
