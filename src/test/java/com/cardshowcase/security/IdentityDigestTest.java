package com.cardshowcase.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Java side of the U1 identity contract. Database equivalence for the same pairs is
 * verified against real PostgreSQL in the isolated harness; these assertions only pin the
 * key function itself (per-code-point simple uppercase, then SHA-256 hex).
 */
class IdentityDigestTest {

    private static final String KELVIN = "K";          // KELVIN SIGN
    private static final String DOTTED_I = "İ";        // LATIN CAPITAL LETTER I WITH DOT ABOVE
    private static final String DOTLESS_I = "ı";       // LATIN SMALL LETTER DOTLESS I
    private static final String FINAL_SIGMA = "ς";
    private static final String MEDIAL_SIGMA = "σ";
    private static final String FI_LIGATURE = "ﬁ";

    @Test
    void keysAreAlways64HexCharacters_evenForLongOrOddInput() {
        String veryLong = "x".repeat(50_000) + "@example.com";
        for (String key : new String[]{
                IdentityDigest.customerKey(veryLong), IdentityDigest.adminKey(veryLong),
                IdentityDigest.customerKey(""), IdentityDigest.adminKey(""),
                IdentityDigest.customerKey("😀@example.com")}) {
            assertThat(key).hasSize(64).matches("[0-9a-f]{64}");
        }
    }

    @Test
    void adminKeyIsExactAndCaseSensitive() {
        assertThat(IdentityDigest.adminKey("admin")).isNotEqualTo(IdentityDigest.adminKey("Admin"));
        assertThat(IdentityDigest.adminKey("admin")).isEqualTo(IdentityDigest.adminKey("admin"));
    }

    @Test
    void customerKeyFoldsCasePerCodePoint_matchingTheDatabasePredicateForTestedPairs() {
        // same account in PostgreSQL upper(email)=upper(?) → same key
        assertSameKey("user@example.com", "USER@EXAMPLE.COM");
        assertSameKey("müller@example.com", "MÜLLER@EXAMPLE.COM");
        assertSameKey(DOTLESS_I + "s" + DOTLESS_I + "k@example.com", "ISIK@EXAMPLE.COM");
        assertSameKey("οδυσσεύ" + FINAL_SIGMA + "@example.com", "ΟΔΥΣΣΕΎΣ@EXAMPLE.COM");
        assertSameKey("οδυσσεύ" + MEDIAL_SIGMA + "@example.com", "οδυσσεύ" + FINAL_SIGMA + "@example.com");
        assertSameKey("ÿves@example.com", "ŸVES@EXAMPLE.COM");
        assertSameKey("Дмитрий@example.com", "дмитрий@example.com");
        assertSameKey("ǅemal@example.com", "ǆemal@example.com");

        // distinct accounts in PostgreSQL → distinct keys (String.toUpperCase would merge these)
        assertDifferentKey("straße@example.com", "STRASSE@EXAMPLE.COM");
        assertDifferentKey(FI_LIGATURE + "sh@example.com", "FISH@EXAMPLE.COM");
        assertDifferentKey("ŉx@example.com", "ʼNX@EXAMPLE.COM");
        assertDifferentKey("ǰx@example.com", "J̌X@EXAMPLE.COM");
        // distinct accounts in PostgreSQL → distinct keys (String.toLowerCase would merge these)
        assertDifferentKey(KELVIN + "elvin@example.com", "kelvin@example.com");
        assertDifferentKey(DOTTED_I + "STANBUL@EXAMPLE.COM", "istanbul@example.com");
    }

    @Test
    void simpleUpperIsOneToOne() {
        assertThat(IdentityDigest.simpleUpper("straße")).isEqualTo("STRAßE");
        assertThat(IdentityDigest.simpleUpper(FI_LIGATURE)).isEqualTo(FI_LIGATURE);
        assertThat(IdentityDigest.simpleUpper("i" + "̇")).isEqualTo("I" + "̇");
        assertThat(IdentityDigest.simpleUpper(KELVIN)).isEqualTo(KELVIN);
        assertThat(IdentityDigest.simpleUpper("😀a")).isEqualTo("😀A");
    }

    private static void assertSameKey(String a, String b) {
        assertThat(IdentityDigest.customerKey(a)).as("%s vs %s", a, b).isEqualTo(IdentityDigest.customerKey(b));
    }

    private static void assertDifferentKey(String a, String b) {
        assertThat(IdentityDigest.customerKey(a)).as("%s vs %s", a, b).isNotEqualTo(IdentityDigest.customerKey(b));
    }
}
