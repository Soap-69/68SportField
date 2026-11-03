package com.cardshowcase.security;

import java.util.Locale;

/** The customer email stored at registration and used for login/reset lookup and budgets. */
public final class CustomerEmail {
    private CustomerEmail() {
    }

    public static String normalize(String submittedEmail) {
        return submittedEmail.trim().toLowerCase(Locale.ROOT);
    }
}
