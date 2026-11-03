package com.cardshowcase.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Retained identity keys for the attempt limiters: always a 64-character SHA-256 hex digest,
 * never the submitted string. ADMIN digests the username exactly as the form filter delivers
 * it (already trimmed; the admin lookup is case-sensitive). CUSTOMER and forgot-password
 * digest the per-code-point simple uppercase of the endpoint's actual lookup input, which
 * follows PostgreSQL's {@code upper(email) = upper(?)} predicate for the verified runtime/collation.
 * Customer input must first pass through {@link CustomerEmail#normalize(String)}, as it does
 * for the database lookup; raw form input is not the lookup identity.
 */
public final class IdentityDigest {

    private IdentityDigest() {
    }

    public static String adminKey(String usernameAsReceived) {
        return sha256Hex(usernameAsReceived);
    }

    public static String customerKey(String lookupIdentity) {
        return sha256Hex(simpleUpper(lookupIdentity));
    }

    /** Maps each Unicode code point with {@link Character#toUpperCase(int)}; no String case conversion. */
    static String simpleUpper(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> sb.appendCodePoint(Character.toUpperCase(cp)));
        return sb.toString();
    }

    static String sha256Hex(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
