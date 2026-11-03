package com.cardshowcase.service;

import com.cardshowcase.model.entity.Customer;
import com.cardshowcase.model.entity.PasswordResetToken;
import com.cardshowcase.repository.CustomerRepository;
import com.cardshowcase.repository.PasswordResetTokenRepository;
import com.cardshowcase.security.CustomerEmail;
import com.cardshowcase.security.ForgotPasswordBudget;
import com.cardshowcase.security.IdentityDigest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$");
    private static final String INVALID_LINK = "Invalid or expired reset link.";

    private final CustomerRepository customerRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final EmailService emailService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final ForgotPasswordBudget forgotPasswordBudget;

    @Value("${app.base-url}")
    private String baseUrl;

    /**
     * The lookup identity is computed once here (trim + root-locale lowercase) and used for
     * both the request budget and the customer query. Budget admission happens before the
     * query and does not depend on whether the account exists; a rejected request issues no
     * token and sends no mail. Issuance takes the customer row lock so it serializes with
     * resets: a token committed before a reset acquires the lock is invalidated by that reset,
     * one issued after the reset commits is valid.
     */
    @Transactional
    public void requestPasswordReset(String submittedEmail) {
        String lookupEmail = CustomerEmail.normalize(submittedEmail);
        if (!forgotPasswordBudget.tryAccept(IdentityDigest.customerKey(lookupEmail))) {
            return;
        }
        // Silently return if email not found — don't reveal existence
        customerRepository.findByEmailIgnoreCase(lookupEmail).ifPresent(found -> {
            Customer customer = customerRepository.findByIdForUpdate(found.getId()).orElseThrow();
            String token = UUID.randomUUID().toString();
            PasswordResetToken resetToken = PasswordResetToken.builder()
                    .customer(customer)
                    .token(token)
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .used(false)
                    .build();
            tokenRepository.save(resetToken);

            String resetLink = baseUrl + "/reset-password?token=" + token;
            emailService.sendPasswordResetEmail(
                    customer.getEmail(),
                    customer.getFirstName(),
                    resetLink);
            log.info("Password reset token created for customerId={}", customer.getId());
        });
    }

    /**
     * Single-use consumption coordinated per customer: resolve the owner from a scalar
     * projection, lock the customer row, then read the token's mutable state for the first
     * time and validate it, change the password and consume this token together with every
     * sibling in one statement, all in one transaction.
     */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        PasswordResetTokenRepository.TokenOwner owner = tokenRepository.findOwnerByToken(token)
                .orElseThrow(() -> new IllegalArgumentException(INVALID_LINK));

        Customer customer = customerRepository.findByIdForUpdate(owner.getCustomerId())
                .orElseThrow(() -> new IllegalArgumentException(INVALID_LINK));

        PasswordResetToken resetToken = tokenRepository.findById(owner.getTokenId())
                .filter(t -> t.getCustomer().getId().equals(customer.getId()))
                .filter(t -> !Boolean.TRUE.equals(t.getUsed()))
                .filter(t -> t.getExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new IllegalArgumentException(INVALID_LINK));

        if (!PASSWORD_PATTERN.matcher(newPassword).matches()) {
            throw new IllegalArgumentException(
                    "Password must be at least 8 characters and contain at least one uppercase letter, one lowercase letter, and one digit.");
        }

        customer.setPasswordHash(passwordEncoder.encode(newPassword));
        int consumed = tokenRepository.markAllUnusedAsUsed(customer.getId());

        log.info("Password reset completed for customerId={} (tokens consumed: {}, presented tokenId={})",
                customer.getId(), consumed, resetToken.getId());
    }
}
