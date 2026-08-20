package com.cardshowcase.service;

import com.cardshowcase.model.entity.Customer;
import com.cardshowcase.model.entity.PasswordResetToken;
import com.cardshowcase.repository.CustomerRepository;
import com.cardshowcase.repository.PasswordResetTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final CustomerRepository customerRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final EmailService emailService;
    private final BCryptPasswordEncoder passwordEncoder;

    @Transactional
    public void requestPasswordReset(String email, String resetBaseUrl) {
        // Silently return if email not found — don't reveal existence
        customerRepository.findByEmailIgnoreCase(email).ifPresent(customer -> {
            String token = UUID.randomUUID().toString();
            PasswordResetToken resetToken = PasswordResetToken.builder()
                    .customer(customer)
                    .token(token)
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .used(false)
                    .build();
            tokenRepository.save(resetToken);

            String resetLink = resetBaseUrl + "/reset-password?token=" + token;
            emailService.sendPasswordResetEmail(
                    customer.getEmail(),
                    customer.getFirstName(),
                    resetLink);
            log.info("Password reset token created for customerId={}", customer.getId());
        });
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        PasswordResetToken resetToken = tokenRepository.findByTokenAndUsedFalse(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired reset link."));

        if (resetToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("This reset link has expired. Please request a new one.");
        }

        if (!PASSWORD_PATTERN.matcher(newPassword).matches()) {
            throw new IllegalArgumentException(
                    "Password must be at least 8 characters and contain at least one uppercase letter, one lowercase letter, and one digit.");
        }

        Customer customer = resetToken.getCustomer();
        customer.setPasswordHash(passwordEncoder.encode(newPassword));
        customerRepository.save(customer);

        resetToken.setUsed(true);
        tokenRepository.save(resetToken);

        log.info("Password reset completed for customerId={}", customer.getId());
    }
}
