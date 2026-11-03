package com.cardshowcase;

import com.cardshowcase.model.dto.RegisterDTO;
import com.cardshowcase.model.entity.Customer;
import com.cardshowcase.model.entity.PasswordResetToken;
import com.cardshowcase.repository.CustomerRepository;
import com.cardshowcase.repository.PasswordResetTokenRepository;
import com.cardshowcase.security.ForgotPasswordBudget;
import com.cardshowcase.security.IdentityDigest;
import com.cardshowcase.service.CustomerAuthService;
import com.cardshowcase.service.PasswordResetService;
import com.cardshowcase.support.MailMessages;
import com.cardshowcase.support.RecordingMailSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

/**
 * T14 (sequential reset rules on H2), T17 (forgot-password budget) and the reset parts of T20.
 * Row-lock behaviour under real concurrency is proven against PostgreSQL in the isolated
 * harness, not here.
 */
class PasswordResetSecurityTest extends BaseIntegrationTest {

    private static final String FLASH =
            "If an account exists with this email, we've sent a reset link. Please check your inbox.";

    @Autowired CustomerAuthService customerAuthService;
    @Autowired CustomerRepository customerRepository;
    @Autowired PasswordResetTokenRepository tokenRepository;
    @Autowired PasswordResetService passwordResetService;
    @Autowired BCryptPasswordEncoder passwordEncoder;
    @Autowired ForgotPasswordBudget budget;
    @Autowired JavaMailSender mailSender;

    private RecordingMailSender mail;
    private long ts;

    @BeforeEach
    void setUp() {
        mail = (RecordingMailSender) mailSender;
        mail.reset();
        ts = System.nanoTime();
    }

    @AfterEach
    void cleanTokens() {
        tokenRepository.deleteAll();
    }

    private Customer register(String email) {
        RegisterDTO dto = new RegisterDTO();
        dto.setEmail(email);
        dto.setPassword("Password1");
        dto.setConfirmPassword("Password1");
        dto.setFirstName("Reset");
        dto.setLastName("Test");
        return customerAuthService.register(dto);
    }

    /** Stores the address exactly as given (bypasses registration lowercasing) for identity tests. */
    private Customer storeExact(String email) {
        return customerRepository.save(Customer.builder()
                .email(email).passwordHash(passwordEncoder.encode("Password1"))
                .firstName("Exact").lastName("Case").isActive(true).emailVerified(false).build());
    }

    /** Mails recorded for one recipient; other tests' asynchronous sends may land late. */
    private List<jakarta.mail.internet.MimeMessage> mailsTo(String email) {
        return mail.sent().stream().filter(m -> email.equals(MailMessages.firstRecipient(m))).toList();
    }

    private List<PasswordResetToken> tokensOf(Long customerId) {
        return tokenRepository.findAll().stream()
                .filter(t -> t.getCustomer().getId().equals(customerId)).toList();
    }

    private MockHttpServletRequestBuilder forgot(String email) {
        return post("/forgot-password").with(csrf()).param("email", email);
    }

    private void forgotOk(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(redirectedUrl("/forgot-password"))
                .andExpect(flash().attribute("successMessage", FLASH));
    }

    // ── T14 ───────────────────────────────────────────────────────

    @Test
    void t14_consumingOneTokenInvalidatesSiblings_unrelatedCustomerUnaffected() {
        Customer a = register("t14a-" + ts + "@w9.test");
        Customer b = register("t14b-" + ts + "@w9.test");
        passwordResetService.requestPasswordReset(a.getEmail());
        passwordResetService.requestPasswordReset(a.getEmail());
        passwordResetService.requestPasswordReset(b.getEmail());
        List<PasswordResetToken> aTokens = tokensOf(a.getId());
        assertThat(aTokens).hasSize(2);
        String first = aTokens.get(0).getToken(), second = aTokens.get(1).getToken();

        passwordResetService.resetPassword(first, "Rotated-Pass1");

        assertThat(tokensOf(a.getId())).allMatch(PasswordResetToken::getUsed);
        assertThat(passwordEncoder.matches("Rotated-Pass1",
                customerRepository.findById(a.getId()).orElseThrow().getPasswordHash())).isTrue();
        assertThatThrownBy(() -> passwordResetService.resetPassword(second, "Another-Pass1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid or expired reset link.");
        assertThatThrownBy(() -> passwordResetService.resetPassword(first, "Another-Pass1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid or expired reset link.");
        assertThat(passwordEncoder.matches("Rotated-Pass1",
                customerRepository.findById(a.getId()).orElseThrow().getPasswordHash())).isTrue();

        // b's token is untouched and still works
        String bToken = tokensOf(b.getId()).get(0).getToken();
        assertThat(tokensOf(b.getId()).get(0).getUsed()).isFalse();
        passwordResetService.resetPassword(bToken, "Other-Pass1");
        assertThat(passwordEncoder.matches("Other-Pass1",
                customerRepository.findById(b.getId()).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void t14_expiredTokenRejected_invalidPasswordLeavesTokenUnused_unknownTokenRejected() {
        Customer a = register("t14c-" + ts + "@w9.test");
        passwordResetService.requestPasswordReset(a.getEmail());
        PasswordResetToken token = tokensOf(a.getId()).get(0);
        String original = customerRepository.findById(a.getId()).orElseThrow().getPasswordHash();

        assertThatThrownBy(() -> passwordResetService.resetPassword(token.getToken(), "short"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 8 characters");
        assertThat(tokenRepository.findById(token.getId()).orElseThrow().getUsed()).isFalse();
        assertThat(customerRepository.findById(a.getId()).orElseThrow().getPasswordHash()).isEqualTo(original);

        token.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        tokenRepository.save(token);
        assertThatThrownBy(() -> passwordResetService.resetPassword(token.getToken(), "Valid-Pass1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid or expired reset link.");
        assertThat(customerRepository.findById(a.getId()).orElseThrow().getPasswordHash()).isEqualTo(original);

        assertThatThrownBy(() -> passwordResetService.resetPassword("no-such-token", "Valid-Pass1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid or expired reset link.");
    }

    @Test
    void t14_resetLinkUsesConfiguredBaseUrl_regardlessOfHostHeaders() throws Exception {
        Customer a = register("t14d-" + ts + "@w9.test");
        forgotOk(forgot(a.getEmail())
                .header("Host", "evil.example")
                .header("X-Forwarded-Host", "evil.example")
                .header("X-Forwarded-Proto", "https"));

        await().atMost(10, TimeUnit.SECONDS).until(() -> mailsTo(a.getEmail()).size() == 1);
        String html = MailMessages.html(mailsTo(a.getEmail()).get(0));
        String token = tokensOf(a.getId()).get(0).getToken();
        assertThat(html).contains("http://localhost/reset-password?token=" + token);
        assertThat(html).doesNotContain("evil.example");
    }

    // ── T17 ───────────────────────────────────────────────────────

    @Test
    void t17_sequential_threeAccepted_fourthCreatesNoTokenAndNoMail_sameResponse() throws Exception {
        Customer a = register("t17a-" + ts + "@w9.test");
        for (int i = 0; i < 3; i++) forgotOk(forgot(a.getEmail()));
        await().atMost(10, TimeUnit.SECONDS).until(() -> mailsTo(a.getEmail()).size() == 3);
        assertThat(tokensOf(a.getId())).hasSize(3);

        forgotOk(forgot(a.getEmail().toUpperCase(Locale.ROOT)));          // 4th, alias form: rejected
        forgotOk(forgot("  " + a.getEmail() + "  "));                      // 5th: rejected
        assertThat(tokensOf(a.getId())).hasSize(3);
        assertThat(mailsTo(a.getEmail())).hasSize(3);
        assertThat(budget.accepted(IdentityDigest.customerKey(a.getEmail()))).isEqualTo(3);
    }

    @Test
    void t17_concurrentBurst_acceptsExactlyThree() throws Exception {
        Customer a = register("t17b-" + ts + "@w9.test");
        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    forgotOk(forgot(a.getEmail()));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(tokensOf(a.getId())).hasSize(3);
        await().atMost(10, TimeUnit.SECONDS).until(() -> mailsTo(a.getEmail()).size() == 3);
        assertThat(budget.accepted(IdentityDigest.customerKey(a.getEmail()))).isEqualTo(3);
    }

    @Test
    void t17_unknownIdentity_sameResponse_budgetConsumed_noTokenNoMail() throws Exception {
        String unknown = "ghost-" + ts + "@w9.test";
        for (int i = 0; i < 4; i++) forgotOk(forgot(unknown));
        assertThat(budget.accepted(IdentityDigest.customerKey(unknown))).isEqualTo(3);
        assertThat(tokenRepository.findAll()).noneMatch(t -> t.getCustomer().getEmail().equals(unknown));
        assertThat(mailsTo(unknown)).isEmpty();
    }

    // ── T20 (reset side) ──────────────────────────────────────────

    @Test
    void t20_dottedIFormsLowercaseToOneLookupIdentity_andShareOneBudget() throws Exception {
        // the account as registration stores it today: i + U+0307 ("combining dot above")
        String stored = "i̇stanbul-" + ts + "@w9.test";
        Customer c = storeExact(stored);
        String dotted = "İSTANBUL-" + ts + "@W9.TEST";        // U+0130, as a user would type it
        String mixed = "İstanbul-" + ts + "@w9.test";

        assertThat(dotted.trim().toLowerCase(Locale.ROOT)).isEqualTo(stored);
        forgotOk(forgot(dotted));
        forgotOk(forgot(stored));
        forgotOk(forgot(mixed));
        assertThat(tokensOf(c.getId())).hasSize(3);
        forgotOk(forgot(dotted));                                     // 4th mixed-form request: nothing
        assertThat(tokensOf(c.getId())).hasSize(3);
        await().atMost(10, TimeUnit.SECONDS).until(() -> mailsTo(stored).size() == 3);
        assertThat(budget.accepted(IdentityDigest.customerKey(stored))).isEqualTo(3);
    }

    @Test
    void t20_kelvinSignRequestResolvesToTheAsciiAccountThroughRootLowercase() throws Exception {
        Customer kelvin = register("kelvin-" + ts + "@w9.test");
        String typed = "Kelvin-" + ts + "@w9.test";                 // KELVIN SIGN
        assertThat(typed.toLowerCase(Locale.ROOT)).isEqualTo(kelvin.getEmail());

        forgotOk(forgot(typed));
        assertThat(tokensOf(kelvin.getId())).hasSize(1);
        assertThat(budget.accepted(IdentityDigest.customerKey(kelvin.getEmail()))).isEqualTo(1);
    }
}
