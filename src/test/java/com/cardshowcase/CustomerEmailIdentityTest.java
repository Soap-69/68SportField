package com.cardshowcase;

import com.cardshowcase.model.dto.RegisterDTO;
import com.cardshowcase.model.entity.Customer;
import com.cardshowcase.repository.CustomerRepository;
import com.cardshowcase.service.CustomerAuthService;
import com.cardshowcase.service.CustomerPrincipal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Registration, later login and throttling must agree on the submitted email identity. */
class CustomerEmailIdentityTest extends BaseIntegrationTest {
    private static final String PASSWORD = "Unicode-Pass1";
    @Autowired CustomerAuthService customerAuthService;
    @Autowired CustomerRepository customerRepository;

    private RegisterDTO registration(String email) {
        RegisterDTO dto = new RegisterDTO();
        dto.setEmail(email);
        dto.setPassword(PASSWORD);
        dto.setConfirmPassword(PASSWORD);
        dto.setFirstName("Unicode");
        dto.setLastName("Identity");
        return dto;
    }

    private void loginAs(String email, Customer expected) throws Exception {
        var result = mockMvc.perform(post("/login").with(csrf())
                .param("username", email).param("password", PASSWORD))
            .andExpect(redirectedUrl("/account")).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        var context = (SecurityContext) session.getAttribute(
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        var principal = (CustomerPrincipal) context.getAuthentication().getPrincipal();
        assertThat(principal.getUsername()).isEqualTo(expected.getEmail());
        mockMvc.perform(get("/account/profile").session(session)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource({"İSTANBUL, i̇stanbul", "Kelvin, kelvin"})
    void registeredAddressCanLogInAgainAfterLogout(String typedPrefix, String storedPrefix) throws Exception {
        String suffix = "-" + System.nanoTime() + "@example.test";
        String typed = typedPrefix + suffix;
        String stored = storedPrefix + suffix;
        var anonymous = new MockHttpSession();
        mockMvc.perform(get("/register").session(anonymous)).andExpect(status().isOk());
        var registered = mockMvc.perform(post("/register").with(csrf()).session(anonymous)
                .param("email", typed).param("password", PASSWORD).param("confirmPassword", PASSWORD)
                .param("firstName", "Unicode").param("lastName", "Identity"))
            .andExpect(redirectedUrl("/account")).andReturn();
        var session = (MockHttpSession) registered.getRequest().getSession(false);
        mockMvc.perform(post("/logout").with(csrf()).session(session)).andExpect(redirectedUrl("/"));
        Customer customer = customerRepository.findByEmailIgnoreCase(stored).orElseThrow();
        assertThat(customer.getEmail()).isEqualTo(stored);
        loginAs(typed, customer);
        loginAs("  " + stored + "  ", customer);
        assertThatThrownBy(() -> customerAuthService.register(registration(typed)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("already exists");
    }

    @ParameterizedTest
    @CsvSource({"İSTANBUL, i̇stanbul", "Kelvin, kelvin"})
    void typedAndStoredFormsShareTheFiveAttemptLoginBudget(String typedPrefix, String storedPrefix) throws Exception {
        String suffix = "-" + System.nanoTime() + "@example.test";
        String typed = typedPrefix + suffix;
        String stored = storedPrefix + suffix;
        customerAuthService.register(registration(typed));
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/login").with(csrf())
                    .param("username", i % 2 == 0 ? typed : stored).param("password", "Wrong-Pass1"))
                .andExpect(redirectedUrl("/login?error"));
        }
        for (String input : new String[]{typed, stored, "  " + typed + "  "}) {
            mockMvc.perform(post("/login").with(csrf()).param("username", input).param("password", PASSWORD))
                .andExpect(redirectedUrl("/login?locked"));
        }
    }

    @ParameterizedTest
    // Sharp-s/SS and ligature/plain-letter distinctions require PostgreSQL: H2 folds them differently.
    @CsvSource({"İSTANBUL, istanbul"})
    void distinctStoredAddressesKeepSeparateAccountsAndBudgets(String first, String second) throws Exception {
        String suffix = "-" + System.nanoTime() + "@example.test";
        Customer a = customerAuthService.register(registration(first + suffix));
        Customer b = customerAuthService.register(registration(second + suffix));
        assertThat(a.getId()).isNotEqualTo(b.getId());
        assertThat(a.getEmail()).isNotEqualTo(b.getEmail());
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/login").with(csrf()).param("username", first + suffix)
                    .param("password", "Wrong-Pass1"))
                .andExpect(redirectedUrl("/login?error"));
        }
        mockMvc.perform(post("/login").with(csrf()).param("username", first + suffix)
                .param("password", PASSWORD)).andExpect(redirectedUrl("/login?locked"));
        loginAs(second + suffix, b);
    }

    @ParameterizedTest
    @CsvSource({"en-US", "tr-TR"})
    void customerLoginUsesRootLocaleRegardlessOfTheDefault(String languageTag) throws Exception {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag(languageTag));
            String typed = "İSTANBUL-" + System.nanoTime() + "@EXAMPLE.TEST";
            Customer customer = customerAuthService.register(registration(typed));
            assertThat(customer.getEmail()).isEqualTo(typed.toLowerCase(Locale.ROOT));
            loginAs(typed, customer);
        } finally {
            Locale.setDefault(original);
        }
    }
}
