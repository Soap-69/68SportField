package com.cardshowcase;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies that standard session-based CSRF protection is correctly applied to
 * the customer login form and that admin endpoints still require authentication.
 */
class CsrfIntegrationTest extends BaseIntegrationTest {

    /** Login page must contain the hidden _csrf field that th:action injects. */
    @Test
    void loginPage_containsCsrfHiddenField() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("_csrf")));
    }

    /** POST without a CSRF token must be rejected with 403. */
    @Test
    void postLogin_withoutCsrf_returns403() throws Exception {
        mockMvc.perform(post("/login")
                .param("username", "anyone@example.com")
                .param("password", "Password1"))
                .andExpect(status().isForbidden());
    }

    /**
     * POST with a valid CSRF token must pass CSRF filtering.
     * Credentials are intentionally wrong → redirect to /login?error (302),
     * not to 403 (which would mean CSRF was still rejected).
     */
    @Test
    void postLogin_withValidCsrf_passesCsrfValidation() throws Exception {
        mockMvc.perform(post("/login")
                .with(csrf())
                .param("username", "nobody@example.com")
                .param("password", "WrongPassword1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    /** Admin dashboard must redirect unauthenticated requests. */
    @Test
    void adminDashboard_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection());
    }

    /**
     * POST to /api/cart/add without a CSRF token must be rejected with 403.
     * This applies to anonymous guests — the primary usage path.
     */
    @Test
    void cartAdd_withoutCsrf_returns403() throws Exception {
        mockMvc.perform(post("/api/cart/add")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"variantId\":1,\"quantity\":1}"))
                .andExpect(status().isForbidden());
    }

    /**
     * POST to /api/cart/add with a valid CSRF token must pass CSRF filtering for
     * an anonymous guest (no login session). The endpoint returns JSON — a non-403
     * response proves CSRF validation succeeded on the anonymous path.
     */
    @Test
    void cartAdd_withCsrf_anonymous_passesCsrfValidation() throws Exception {
        mockMvc.perform(post("/api/cart/add")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"variantId\":999999,\"quantity\":1}"))
                .andExpect(status().isOk())            // CSRF passed; business logic may reject unknown variant
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
}
