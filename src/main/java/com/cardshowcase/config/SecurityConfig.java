package com.cardshowcase.config;

import com.cardshowcase.repository.AdminUserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AdminUserRepository adminUserRepository;

    public SecurityConfig(AdminUserRepository adminUserRepository) {
        this.adminUserRepository = adminUserRepository;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth

                // ── Admin area ──────────────────────────────────────────────
                .requestMatchers("/admin/login").permitAll()
                .requestMatchers("/admin/**").hasAnyRole("ADMIN", "SENIOR_ADMIN")

                // ── Customer area (future: require ROLE_CUSTOMER) ───────────
                // TODO: replace permitAll with .hasRole("CUSTOMER") once auth is implemented
                .requestMatchers("/account/**").permitAll()
                .requestMatchers("/checkout/**").permitAll()

                // ── Cart API (future: require ROLE_CUSTOMER or session cart) ─
                // TODO: replace permitAll with proper cart auth once implemented
                .requestMatchers("/api/cart/**").permitAll()

                // ── Inquiry API (AJAX, no session) ──────────────────────────
                .requestMatchers("/api/inquiry").permitAll()

                // ── Payment webhooks (future: verify provider signature) ─────
                // TODO: add signature verification filter before removing permitAll
                .requestMatchers("/api/payment/webhook/**").permitAll()

                // ── Public storefront ────────────────────────────────────────
                .requestMatchers(
                    "/",
                    "/product/**",
                    "/category/**",
                    "/products",
                    "/search",
                    "/inquiry",
                    "/login",
                    "/register"
                ).permitAll()

                // Static resources
                .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()

                .anyRequest().permitAll()
            )
            .csrf(csrf -> csrf
                // Disable CSRF for stateless API endpoints only
                .ignoringRequestMatchers(
                    new AntPathRequestMatcher("/api/inquiry"),
                    new AntPathRequestMatcher("/api/cart/**"),
                    // TODO: replace with signature-based verification once implemented
                    new AntPathRequestMatcher("/api/payment/webhook/**")
                )
            )
            .formLogin(form -> form
                .loginPage("/admin/login")
                .loginProcessingUrl("/admin/login")
                .defaultSuccessUrl("/admin", true)
                .failureUrl("/admin/login?error")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/admin/logout")
                .logoutSuccessUrl("/admin/login?logout")
                .permitAll()
            );
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            var adminUser = adminUserRepository.findByUsername(username)
                    .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
            if (Boolean.FALSE.equals(adminUser.getIsActive())) {
                throw new UsernameNotFoundException("User is inactive: " + username);
            }
            return User.builder()
                    .username(adminUser.getUsername())
                    .password(adminUser.getPassword())
                    .roles(adminUser.getRole())
                    .build();
        };
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        var provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService());
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }
}
