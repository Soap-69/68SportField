package com.cardshowcase.config;

import com.cardshowcase.repository.AdminUserRepository;
import com.cardshowcase.service.CustomLoginSuccessHandler;
import com.cardshowcase.service.CustomerUserDetailsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import java.util.function.Supplier;

@Slf4j
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AdminUserRepository adminUserRepository;
    private final CustomerUserDetailsService customerUserDetailsService;
    private final CustomLoginSuccessHandler customLoginSuccessHandler;

    public SecurityConfig(AdminUserRepository adminUserRepository,
                          CustomerUserDetailsService customerUserDetailsService,
                          CustomLoginSuccessHandler customLoginSuccessHandler) {
        this.adminUserRepository = adminUserRepository;
        this.customerUserDetailsService = customerUserDetailsService;
        this.customLoginSuccessHandler = customLoginSuccessHandler;
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // ── Chain 1: Admin (highest priority, scoped to /admin/**) ────
    @Bean
    @Order(1)
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/admin/**")
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/admin/login").permitAll()
                .requestMatchers("/admin/**").hasAnyRole("ADMIN", "SENIOR_ADMIN")
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
            )
            .authenticationProvider(adminAuthenticationProvider());
        return http.build();
    }

    // ── Chain 2: Customer + Public (everything not under /admin/**) ─
    @Bean
    @Order(2)
    public SecurityFilterChain customerSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth

                // Customer account area — requires ROLE_CUSTOMER
                .requestMatchers("/account/**").hasRole("CUSTOMER")

                // Customer auth pages and public storefront — open
                .requestMatchers(
                    "/login", "/register", "/forgot-password", "/reset-password",
                    "/cart", "/checkout", "/checkout/**",
                    "/", "/product/**", "/category/**", "/products", "/search", "/inquiry"
                ).permitAll()

                // Stateless API endpoints
                .requestMatchers("/api/cart/**", "/api/inquiry", "/api/payment/webhook/**").permitAll()

                // Static resources
                .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()

                .anyRequest().permitAll()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .defaultSuccessUrl("/account", true)
                .failureUrl("/login?error")
                .successHandler(customLoginSuccessHandler)
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/")
                .deleteCookies("JSESSIONID")
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .permitAll()
            )
            .csrf(csrf -> csrf
                // Standard session-based CSRF (HttpSessionCsrfTokenRepository) — correct for
                // a server-rendered Thymeleaf app. th:action injects the _csrf hidden field
                // automatically; no XSRF-TOKEN cookie is needed.
                //
                // The handler override forces eager token materialisation: without it,
                // Spring Security 6.x defers saveToken() until th:action is processed.
                // Our public layout (Bootstrap CDN, full navbar, mega-menu, off-canvas) is
                // large enough to fill Tomcat's 8 KB response buffer and commit the response
                // before the form is reached, at which point request.getSession(true) throws
                // "Cannot create a session after the response has been committed". Calling
                // deferredCsrfToken.get() here runs saveToken() while the response is still
                // open, before any template byte has been written.
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler() {
                    @Override
                    public void handle(jakarta.servlet.http.HttpServletRequest request,
                                       jakarta.servlet.http.HttpServletResponse response,
                                       Supplier<CsrfToken> deferredCsrfToken) {
                        super.handle(request, response, deferredCsrfToken);
                        // ── CSRF timing fix ───────────────────────────────────────────────────
                        // The main.html layout (navbar + mega-menu + off-canvas) exceeds Tomcat's
                        // 8 KB response buffer before <form th:action> is reached.  Without this
                        // eager get(), HttpSessionCsrfTokenRepository.saveToken() would attempt
                        // request.getSession(true) AFTER the response committed, throwing
                        // "Cannot create a session after the response has been committed", leaving
                        // no token in the session and causing a 403 on the subsequent POST.
                        //
                        // Calling get() here forces saveToken() while the response is still open.
                        // When th:action later calls getExtraHiddenFields() the Supplier is already
                        // resolved (cached) and returns the stored token without touching the session.
                        //
                        // The committed-state log below is a canary: it should ALWAYS print false.
                        // If it ever prints true, a filter ahead of CsrfFilter is flushing bytes,
                        // which would mean this fix no longer protects the session write.
                        if (response.isCommitted()) {
                            log.error("CSRF-TIMING BUG: response already committed before saveToken() " +
                                      "for {} {} — CSRF token cannot be persisted to session; " +
                                      "next form POST will receive 403",
                                      request.getMethod(), request.getRequestURI());
                        }
                        deferredCsrfToken.get(); // materialise token + write to session NOW
                        log.debug("CSRF token materialised [method={} uri={} committed={}]",
                                  request.getMethod(), request.getRequestURI(),
                                  response.isCommitted());
                    }
                })
                .ignoringRequestMatchers(
                    new AntPathRequestMatcher("/api/inquiry"),
                    new AntPathRequestMatcher("/api/payment/webhook/**")
                )
            )
            .authenticationProvider(customerAuthenticationProvider());
        return http.build();
    }

    // ── Authentication providers ──────────────────────────────────

    @Bean
    public DaoAuthenticationProvider adminAuthenticationProvider() {
        var provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(adminUserDetailsService());
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public DaoAuthenticationProvider customerAuthenticationProvider() {
        var provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(customerUserDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    private UserDetailsService adminUserDetailsService() {
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
}
