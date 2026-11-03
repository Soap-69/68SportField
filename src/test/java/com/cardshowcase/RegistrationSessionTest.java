package com.cardshowcase;

import com.cardshowcase.model.dto.RegisterDTO;
import com.cardshowcase.model.entity.Customer;
import com.cardshowcase.repository.CartRepository;
import com.cardshowcase.repository.CustomerRepository;
import com.cardshowcase.repository.PasswordResetTokenRepository;
import com.cardshowcase.service.CustomerAuthService;
import com.cardshowcase.service.PasswordResetService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T15 (registration rotates the session id, then persists the context) and the locale half of T20. */
class RegistrationSessionTest extends BaseIntegrationTest {

    @Autowired CustomerAuthService customerAuthService;
    @Autowired CustomerRepository customerRepository;
    @Autowired CartRepository cartRepository;
    @Autowired PasswordResetService passwordResetService;
    @Autowired PasswordResetTokenRepository tokenRepository;

    private final long ts = System.nanoTime();

    @AfterEach
    void cleanTokens() {
        tokenRepository.deleteAll();
    }

    @Test
    void t15_registrationRotatesTheSessionId_andTheNewSessionIsAuthenticated() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/register").session(session)).andExpect(status().isOk());
        String anonymousId = session.getId();
        String email = "t15-" + ts + "@w9.test";

        MvcResult result = mockMvc.perform(post("/register").with(csrf()).session(session)
                        .param("email", email).param("password", "Password1").param("confirmPassword", "Password1")
                        .param("firstName", "Rota").param("lastName", "Tion"))
                .andExpect(redirectedUrl("/account"))
                .andReturn();

        String authenticatedId = result.getRequest().getSession(false).getId();
        assertThat(authenticatedId).isNotEqualTo(anonymousId);
        assertThat(result.getRequest().getSession(false)
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNotNull();
        mockMvc.perform(get("/account/profile").session(session)).andExpect(status().isOk());

        // existing behaviour preserved: registration creates no cart and merges nothing
        Customer c = customerRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(cartRepository.findByCustomerId(c.getId())).isEmpty();
        assertThat(c.getEmail()).isEqualTo(email);
    }

    @Test
    void t15_anonymousSessionWithoutRegistrationIsNotAuthenticated() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/register").session(session)).andExpect(status().isOk());
        mockMvc.perform(get("/account/profile").session(session)).andExpect(status().is3xxRedirection());
    }

    @Test
    void t20_registrationAndResetLookupUseRootLocale_underATurkishDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat("ISIK".toLowerCase()).isEqualTo("ısık");           // the trap this guards against

            String typed = "ISIK-T20-" + ts + "@W9.TEST";
            RegisterDTO dto = new RegisterDTO();
            dto.setEmail(typed);
            dto.setPassword("Password1");
            dto.setConfirmPassword("Password1");
            dto.setFirstName("Root");
            dto.setLastName("Locale");
            Customer c = customerAuthService.register(dto);
            assertThat(c.getEmail()).isEqualTo("isik-t20-" + ts + "@w9.test");

            passwordResetService.requestPasswordReset(typed);
            assertThat(tokenRepository.findAll())
                    .anySatisfy(t -> assertThat(t.getCustomer().getId()).isEqualTo(c.getId()));
        } finally {
            Locale.setDefault(original);
        }
    }
}
