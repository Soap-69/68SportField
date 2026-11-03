package com.cardshowcase;

import com.cardshowcase.model.dto.RegisterDTO;
import com.cardshowcase.model.entity.AdminUser;
import com.cardshowcase.model.entity.Cart;
import com.cardshowcase.model.entity.Customer;
import com.cardshowcase.repository.AdminUserRepository;
import com.cardshowcase.repository.CartRepository;
import com.cardshowcase.repository.CustomerRepository;
import com.cardshowcase.security.IdentityDigest;
import com.cardshowcase.security.LoginAttemptLimiter;
import com.cardshowcase.security.LoginAttemptLimiter.Admission;
import com.cardshowcase.service.CartService;
import com.cardshowcase.service.CustomerAuthService;
import com.cardshowcase.service.CustomerUserDetailsService;
import com.cardshowcase.support.MutableClockTestConfig;
import com.cardshowcase.support.MutableClockTestConfig.MutableClock;
import com.cardshowcase.support.Week8Fixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockCookie;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T1–T13 through real form POSTs with CSRF tokens against both chains, with a controllable
 * clock and spies that count how often the DAO delegate / customer lookup actually runs.
 */
@Import(MutableClockTestConfig.class)
class LoginThrottleIntegrationTest extends BaseIntegrationTest {

    private static final String PW = "Password1";
    private static final String WRONG = "Wrong-pass-9";

    @Autowired MutableClock clock;
    @Autowired @Qualifier("customerLoginLimiter") LoginAttemptLimiter customerLimiter;
    @Autowired @Qualifier("adminLoginLimiter") LoginAttemptLimiter adminLimiter;
    @Autowired CustomerAuthService customerAuthService;
    @Autowired CustomerRepository customerRepository;
    @Autowired AdminUserRepository adminUserRepository;
    @Autowired BCryptPasswordEncoder passwordEncoder;
    @Autowired CartRepository cartRepository;
    @Autowired com.cardshowcase.repository.CartItemRepository cartItemRepository;
    @Autowired CartService cartService;
    @Autowired Week8Fixture fx;

    @MockitoSpyBean CustomerUserDetailsService customerUserDetailsService;
    @MockitoSpyBean(name = "adminAuthenticationProvider") DaoAuthenticationProvider adminAuthenticationProvider;

    private long ts;

    @BeforeEach
    void freshWindow() {
        // Every test starts an hour later than the previous one, so prior entries have expired.
        clock.advance(Duration.ofHours(1));
        ts = System.nanoTime();
        reset(customerUserDetailsService, adminAuthenticationProvider);
        clearInvocations(customerUserDetailsService, adminAuthenticationProvider);
    }

    // ── fixtures and helpers ──────────────────────────────────────

    private String newCustomer(String prefix) {
        RegisterDTO dto = new RegisterDTO();
        String email = prefix + "-" + ts + "@w9.test";
        dto.setEmail(email);
        dto.setPassword(PW);
        dto.setConfirmPassword(PW);
        dto.setFirstName("Throttle");
        dto.setLastName("Test");
        customerAuthService.register(dto);
        return email;
    }

    private String newAdmin(String prefix) {
        String name = prefix + "-" + ts;
        adminUserRepository.save(AdminUser.builder()
                .username(name).password(passwordEncoder.encode(PW)).role("ADMIN").build());
        return name;
    }

    private static MockHttpServletRequestBuilder customerLogin(String user, String password) {
        return post("/login").with(csrf()).param("username", user).param("password", password);
    }

    private static MockHttpServletRequestBuilder adminLogin(String user, String password) {
        return post("/admin/login").with(csrf()).param("username", user).param("password", password);
    }

    private String redirect(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
    }

    private LoginAttemptLimiter.Snapshot customerSnap(String email) {
        return customerLimiter.snapshot(IdentityDigest.customerKey(email));
    }

    /** Admitted calls for {@code email} block on the returned gate inside the customer lookup. */
    private CountDownLatch holdLookups(String email, AtomicInteger entered) {
        CountDownLatch gate = new CountDownLatch(1);
        doAnswer(inv -> {
            entered.incrementAndGet();
            if (!gate.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("gate never released");
            }
            return inv.callRealMethod();
        }).when(customerUserDetailsService).loadUserByUsername(eq(email));
        return gate;
    }

    /** Only the first lookup for {@code email} blocks on the gate; later ones run immediately. */
    private CountDownLatch holdFirstLookup(String email, AtomicInteger entered) {
        CountDownLatch gate = new CountDownLatch(1);
        doAnswer(inv -> {
            if (entered.incrementAndGet() == 1 && !gate.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("gate never released");
            }
            return inv.callRealMethod();
        }).when(customerUserDetailsService).loadUserByUsername(eq(email));
        return gate;
    }

    private record Burst(List<Future<String>> results, AtomicInteger completed, ExecutorService pool) {
        List<String> join() throws Exception {
            List<String> out = new ArrayList<>();
            try {
                for (Future<String> f : results) out.add(f.get(60, TimeUnit.SECONDS));
            } finally {
                pool.shutdownNow();
            }
            return out;
        }
    }

    private Burst burst(int n, java.util.function.IntFunction<MockHttpServletRequestBuilder> request) {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return mockMvc.perform(request.apply(idx)).andReturn().getResponse().getRedirectedUrl();
                } finally {
                    completed.incrementAndGet();
                }
            }));
        }
        start.countDown();
        return new Burst(futures, completed, pool);
    }

    // ── T1 / T2 ───────────────────────────────────────────────────

    @Test
    void t1_customerLoginSucceeds_andMergesTheGuestCart() throws Exception {
        fx.prepare(new BigDecimal("25.00"), 10);
        String email = newCustomer("t1");
        Customer customer = customerRepository.findByEmailIgnoreCase(email).orElseThrow();
        String token = fx.newGuestToken();
        Cart guestCart = cartRepository.save(Cart.builder().sessionToken(token).build());
        cartService.addToCart(guestCart, fx.variant.getId(), 2);

        String target = redirect(customerLogin(email, PW).cookie(new MockCookie("cart_token", token)));

        assertThat(target).isEqualTo("/account");
        Cart merged = cartRepository.findByCustomerId(customer.getId()).orElseThrow();
        assertThat(cartItemRepository.findByCartIdAndVariantId(merged.getId(), fx.variant.getId()))
                .isPresent()
                .hasValueSatisfying(item -> assertThat(item.getQuantity()).isEqualTo(2));
        assertThat(customerSnap(email)).isNull();
    }

    @Test
    void t2_adminLoginSucceeds() throws Exception {
        String admin = newAdmin("t2");
        assertThat(redirect(adminLogin(admin, PW))).isEqualTo("/admin");
        assertThat(adminLimiter.snapshot(IdentityDigest.adminKey(admin))).isNull();
    }

    // ── T3 ────────────────────────────────────────────────────────

    @Test
    void t3_fifthFailureIsErrorAndLocks_sixthIsLockedWithoutLookup() throws Exception {
        String email = newCustomer("t3");
        for (int i = 0; i < 5; i++) {
            assertThat(redirect(customerLogin(email, WRONG))).isEqualTo("/login?error");
        }
        assertThat(customerSnap(email).failures()).isEqualTo(5);
        assertThat(customerSnap(email).blockedUntil()).isNotNull();
        assertThat(redirect(customerLogin(email, WRONG))).isEqualTo("/login?locked");
        assertThat(redirect(customerLogin(email, PW))).isEqualTo("/login?locked");
        verify(customerUserDetailsService, times(5)).loadUserByUsername(email);
    }

    @Test
    void t3_adminRealmBehavesTheSameWay() throws Exception {
        String admin = newAdmin("t3a");
        for (int i = 0; i < 5; i++) {
            assertThat(redirect(adminLogin(admin, WRONG))).isEqualTo("/admin/login?error");
        }
        assertThat(redirect(adminLogin(admin, WRONG))).isEqualTo("/admin/login?locked");
        assertThat(redirect(adminLogin(admin, PW))).isEqualTo("/admin/login?locked");
        verify(adminAuthenticationProvider, times(5)).authenticate(any());
    }

    // ── T4 ────────────────────────────────────────────────────────

    @Test
    void t4_lockFromFifthFailureOutlivesWindow_andExpiresAtExactInstant() throws Exception {
        String email = newCustomer("t4");
        Instant t0 = clock.instant();
        for (int i = 0; i < 4; i++) {
            clock.set(t0.plus(Duration.ofMinutes(i)));
            assertThat(redirect(customerLogin(email, WRONG))).isEqualTo("/login?error");
        }
        clock.set(t0.plus(Duration.ofMinutes(14)));
        assertThat(redirect(customerLogin(email, WRONG))).isEqualTo("/login?error"); // fifth: counted, lock set
        assertThat(customerSnap(email).blockedUntil()).isEqualTo(t0.plus(Duration.ofMinutes(29)));

        clock.set(t0.plus(Duration.ofMinutes(15)));
        assertThat(redirect(customerLogin(email, PW))).isEqualTo("/login?locked");        // window end ≠ lock end
        clock.set(t0.plus(Duration.ofMinutes(29)).minusMillis(1));
        assertThat(redirect(customerLogin(email, WRONG))).isEqualTo("/login?locked");
        clock.set(t0.plus(Duration.ofMinutes(29)));
        assertThat(redirect(customerLogin(email, WRONG))).isEqualTo("/login?error");      // admitted again
        assertThat(customerSnap(email).failures()).isEqualTo(1);
        assertThat(customerSnap(email).windowStart()).isEqualTo(t0.plus(Duration.ofMinutes(29)));
        assertThat(customerSnap(email).blockedUntil()).isNull();
    }

    @Test
    void t4_fourFailuresExpireAtExactlyFifteenMinutes() throws Exception {
        String email = newCustomer("t4b");
        Instant t0 = clock.instant();
        for (int i = 0; i < 4; i++) {
            clock.set(t0.plus(Duration.ofSeconds(15L * i)));
            assertThat(redirect(customerLogin(email, WRONG))).isEqualTo("/login?error");
        }
        clock.set(t0.plus(Duration.ofMinutes(15)));
        assertThat(redirect(customerLogin(email, WRONG))).isEqualTo("/login?error");
        assertThat(customerSnap(email).failures()).isEqualTo(1);                       // new window
        assertThat(customerSnap(email).blockedUntil()).isNull();
    }

    @Test
    void t4_fifthFailureJustInsideTheWindowReturnsErrorAndLocks() throws Exception {
        String email = newCustomer("t4c");
        Instant t0 = clock.instant();
        for (int i = 0; i < 4; i++) {
            clock.set(t0.plus(Duration.ofSeconds(15L * i)));
            assertThat(redirect(customerLogin(email, WRONG))).isEqualTo("/login?error");
        }
        clock.set(t0.plus(Duration.ofMinutes(15)).minusMillis(1));
        assertThat(redirect(customerLogin(email, WRONG))).isEqualTo("/login?error");
        assertThat(customerSnap(email).blockedUntil()).isEqualTo(t0.plus(Duration.ofMinutes(30)).minusMillis(1));
        assertThat(redirect(customerLogin(email, WRONG))).isEqualTo("/login?locked");
    }

    // ── T5 / T6 ───────────────────────────────────────────────────

    @Test
    void t5_successClearsFailures() throws Exception {
        String email = newCustomer("t5");
        for (int i = 0; i < 4; i++) redirect(customerLogin(email, WRONG));
        assertThat(redirect(customerLogin(email, PW))).isEqualTo("/account");
        assertThat(customerSnap(email)).isNull();
        for (int i = 0; i < 4; i++) {
            assertThat(redirect(customerLogin(email, WRONG))).isEqualTo("/login?error");
        }
        assertThat(customerSnap(email).failures()).isEqualTo(4);
    }

    @Test
    void t6_unknownAndInactiveIdentitiesLockIdentically() throws Exception {
        String unknown = "nobody-" + ts + "@w9.test";
        for (int i = 0; i < 5; i++) {
            assertThat(redirect(customerLogin(unknown, WRONG))).isEqualTo("/login?error");
        }
        assertThat(redirect(customerLogin(unknown, WRONG))).isEqualTo("/login?locked");

        String inactive = newCustomer("t6");
        Customer c = customerRepository.findByEmailIgnoreCase(inactive).orElseThrow();
        c.setIsActive(false);
        customerRepository.save(c);
        for (int i = 0; i < 5; i++) {
            assertThat(redirect(customerLogin(inactive, PW))).isEqualTo("/login?error");
        }
        assertThat(redirect(customerLogin(inactive, PW))).isEqualTo("/login?locked");
    }

    // ── T7 / T8 ───────────────────────────────────────────────────

    @Test
    void t7_asciiAliasesShareOneCustomerBudget_adminCaseStaysDistinct_credentialsUnchanged() throws Exception {
        String email = newCustomer("t7");
        String upper = email.toUpperCase(java.util.Locale.ROOT);
        for (int i = 0; i < 3; i++) assertThat(redirect(customerLogin(upper, WRONG))).isEqualTo("/login?error");
        for (int i = 0; i < 2; i++) assertThat(redirect(customerLogin("  " + email + "  ", WRONG))).isEqualTo("/login?error");
        assertThat(customerSnap(email).failures()).isEqualTo(5);
        assertThat(redirect(customerLogin(email, PW))).isEqualTo("/login?locked");
        clock.advance(Duration.ofMinutes(16));
        assertThat(redirect(customerLogin(upper, PW))).isEqualTo("/account");          // credentials unchanged

        String admin = newAdmin("t7a");
        String adminUpper = admin.toUpperCase(java.util.Locale.ROOT);
        for (int i = 0; i < 5; i++) assertThat(redirect(adminLogin(adminUpper, WRONG))).isEqualTo("/admin/login?error");
        assertThat(redirect(adminLogin(adminUpper, PW))).isEqualTo("/admin/login?locked");
        assertThat(redirect(adminLogin(admin, PW))).isEqualTo("/admin");              // separate key, still logs in
    }

    @Test
    void t8_realmsAreIsolated() throws Exception {
        String shared = "shared-" + ts;
        for (int i = 0; i < 5; i++) assertThat(redirect(adminLogin(shared, WRONG))).isEqualTo("/admin/login?error");
        assertThat(redirect(adminLogin(shared, WRONG))).isEqualTo("/admin/login?locked");
        assertThat(redirect(customerLogin(shared, WRONG))).isEqualTo("/login?error");
        assertThat(customerSnap(shared).failures()).isEqualTo(1);

        for (int i = 0; i < 4; i++) assertThat(redirect(customerLogin(shared, WRONG))).isEqualTo("/login?error");
        assertThat(redirect(customerLogin(shared, WRONG))).isEqualTo("/login?locked");
        String admin = newAdmin("t8");
        assertThat(redirect(adminLogin(admin, PW))).isEqualTo("/admin");
    }

    // ── T9 ────────────────────────────────────────────────────────

    @Test
    void t9a_atFourFailures_aBurstOfTenAdmitsExactlyOne() throws Exception {
        String email = newCustomer("t9a");
        for (int i = 0; i < 4; i++) redirect(customerLogin(email, WRONG));
        AtomicInteger entered = new AtomicInteger();
        CountDownLatch gate = holdLookups(email, entered);

        Burst b = burst(10, i -> customerLogin(email, WRONG));
        await().atMost(20, TimeUnit.SECONDS).until(() -> b.completed().get() == 9 && entered.get() == 1);
        assertThat(customerSnap(email).inFlight()).isEqualTo(1);
        gate.countDown();
        List<String> results = b.join();

        assertThat(results).filteredOn("/login?locked"::equals).hasSize(9);
        assertThat(results).filteredOn("/login?error"::equals).hasSize(1);
        assertThat(entered.get()).isEqualTo(1);
        assertThat(customerSnap(email).failures()).isEqualTo(5);
        assertThat(customerSnap(email).blockedUntil()).isNotNull();
        assertThat(customerSnap(email).inFlight()).isZero();
    }

    @Test
    void t9b_fromZero_aBurstOfTwentyAdmitsExactlyFive() throws Exception {
        String email = newCustomer("t9b");
        AtomicInteger entered = new AtomicInteger();
        CountDownLatch gate = holdLookups(email, entered);

        Burst b = burst(20, i -> customerLogin(email, WRONG));
        await().atMost(20, TimeUnit.SECONDS).until(() -> b.completed().get() == 15 && entered.get() == 5);
        assertThat(customerSnap(email).inFlight()).isEqualTo(5);
        gate.countDown();
        List<String> results = b.join();

        assertThat(results).filteredOn("/login?locked"::equals).hasSize(15);
        assertThat(results).filteredOn("/login?error"::equals).hasSize(5);
        assertThat(entered.get()).isEqualTo(5);
        assertThat(customerSnap(email).failures()).isEqualTo(5);
        assertThat(customerSnap(email).blockedUntil()).isNotNull();
    }

    @Test
    void t9c_twoAccountsInOneBurstEachGetTheirOwnFive() throws Exception {
        String a = newCustomer("t9c-a");
        String bEmail = newCustomer("t9c-b");
        AtomicInteger enteredA = new AtomicInteger(), enteredB = new AtomicInteger();
        CountDownLatch gateA = holdLookups(a, enteredA);
        CountDownLatch gateB = holdLookups(bEmail, enteredB);

        Burst b = burst(20, i -> customerLogin(i % 2 == 0 ? a : bEmail, WRONG));
        await().atMost(20, TimeUnit.SECONDS)
                .until(() -> b.completed().get() == 10 && enteredA.get() == 5 && enteredB.get() == 5);
        gateA.countDown();
        gateB.countDown();
        List<String> results = b.join();

        assertThat(results).filteredOn("/login?locked"::equals).hasSize(10);
        assertThat(results).filteredOn("/login?error"::equals).hasSize(10);
        assertThat(customerSnap(a).failures()).isEqualTo(5);
        assertThat(customerSnap(bEmail).failures()).isEqualTo(5);
    }

    @Test
    void t9d_concurrentSuccessAndFailure_applyInCompletionOrder() throws Exception {
        // wrong completes first, then correct → cleared
        String email = newCustomer("t9d");
        for (int i = 0; i < 3; i++) redirect(customerLogin(email, WRONG));
        AtomicInteger entered = new AtomicInteger();
        CountDownLatch gate = holdFirstLookup(email, entered);
        Burst held = burst(1, i -> customerLogin(email, PW));               // correct request is held first
        await().atMost(20, TimeUnit.SECONDS).until(() -> entered.get() == 1);
        assertThat(customerSnap(email).inFlight()).isEqualTo(1);
        assertThat(redirect(customerLogin(email, WRONG))).isEqualTo("/login?error"); // 3 + 1 held + this = admitted
        assertThat(customerSnap(email).failures()).isEqualTo(4);
        assertThat(redirect(customerLogin(email, WRONG))).isEqualTo("/login?locked"); // 4 + 1 in flight = budget reserved
        gate.countDown();
        assertThat(held.join()).containsExactly("/account");
        assertThat(customerSnap(email)).isNull();                                      // success cleared everything

        // correct completes first, then wrong → new window with one failure
        reset(customerUserDetailsService);
        String email2 = newCustomer("t9d2");
        for (int i = 0; i < 3; i++) redirect(customerLogin(email2, WRONG));
        AtomicInteger entered2 = new AtomicInteger();
        CountDownLatch gate2 = holdFirstLookup(email2, entered2);
        Burst heldWrong = burst(1, i -> customerLogin(email2, WRONG));        // wrong request is held first
        await().atMost(20, TimeUnit.SECONDS).until(() -> entered2.get() == 1);
        assertThat(redirect(customerLogin(email2, PW))).isEqualTo("/account");         // success while wrong in flight
        assertThat(customerSnap(email2).failures()).isZero();
        assertThat(customerSnap(email2).inFlight()).isEqualTo(1);
        gate2.countDown();
        assertThat(heldWrong.join()).containsExactly("/login?error");
        assertThat(customerSnap(email2).failures()).isEqualTo(1);
        assertThat(customerSnap(email2).inFlight()).isZero();
    }

    // ── T10 / T11 ─────────────────────────────────────────────────

    @Test
    void t10_requestsWithoutCsrfAre403AndNeverTouchTheLimiter() throws Exception {
        String email = newCustomer("t10");
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/login").param("username", email).param("password", WRONG))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/admin/login").param("username", email).param("password", WRONG))
                    .andExpect(status().isForbidden());
        }
        assertThat(customerSnap(email)).isNull();
        assertThat(adminLimiter.snapshot(IdentityDigest.adminKey(email))).isNull();
        verify(customerUserDetailsService, never()).loadUserByUsername(any());
    }

    @Test
    void t11_blockedRequestsAreNotCountedAndDoNotExtendTheLock() throws Exception {
        String email = newCustomer("t11");
        for (int i = 0; i < 5; i++) redirect(customerLogin(email, WRONG));
        Instant lockedUntil = customerSnap(email).blockedUntil();
        clearInvocations(customerUserDetailsService);
        for (int i = 0; i < 50; i++) {
            clock.advance(Duration.ofSeconds(10));
            assertThat(redirect(customerLogin(email, i % 2 == 0 ? WRONG : PW))).isEqualTo("/login?locked");
        }
        assertThat(customerSnap(email).blockedUntil()).isEqualTo(lockedUntil);
        assertThat(customerSnap(email).failures()).isEqualTo(5);
        verify(customerUserDetailsService, never()).loadUserByUsername(any());
    }

    // ── T12 / T13 ─────────────────────────────────────────────────

    @Test
    void t12_saturatedLimiterRefusesNewIdentitiesBeforeTheDelegate_andRecoversAfterExpiry() throws Exception {
        String locked = newCustomer("t12-locked");
        for (int i = 0; i < 5; i++) redirect(customerLogin(locked, WRONG));
        String counting = newCustomer("t12-counting");
        redirect(customerLogin(counting, WRONG));
        // Fill with long, distinct, non-expired identities until admission saturates. Entries left
        // by earlier tests have expired (the clock moved an hour) and are swept on the way.
        int filled = 0;
        while (true) {
            String key = IdentityDigest.customerKey("fill-" + ts + "-" + filled + "-" + "q".repeat(500));
            assertThat(key).hasSize(64);
            Admission admission = customerLimiter.admit(key);
            if (admission == Admission.SATURATED) break;
            assertThat(admission).isEqualTo(Admission.ADMITTED);
            customerLimiter.recordFailure(key);
            filled++;
        }
        assertThat(customerLimiter.size()).isEqualTo(10_000);
        assertThat(filled).isBetween(9_000, 10_000);
        clearInvocations(customerUserDetailsService);

        String fresh = newCustomer("t12-fresh");
        assertThat(redirect(customerLogin(fresh, PW))).isEqualTo("/login?locked");       // saturated, fail-closed
        verify(customerUserDetailsService, never()).loadUserByUsername(any());
        assertThat(customerLimiter.size()).isEqualTo(10_000);
        assertThat(redirect(customerLogin(locked, PW))).isEqualTo("/login?locked");       // lock preserved
        assertThat(redirect(customerLogin(counting, WRONG))).isEqualTo("/login?error");  // existing key keeps budget
        assertThat(customerSnap(counting).failures()).isEqualTo(2);

        clock.advance(Duration.ofMinutes(31));
        assertThat(redirect(customerLogin(fresh, PW))).isEqualTo("/account");            // sweep freed capacity
        assertThat(customerLimiter.size()).isLessThan(10);
    }

    @Test
    void t13_serviceFailureIsNotCountedAndReleasesTheReservation() throws Exception {
        String email = newCustomer("t13");
        redirect(customerLogin(email, WRONG));
        doThrow(new InternalAuthenticationServiceException("database unavailable"))
                .when(customerUserDetailsService).loadUserByUsername(eq(email));
        assertThat(redirect(customerLogin(email, PW))).isEqualTo("/login?error");
        assertThat(customerSnap(email).failures()).isEqualTo(1);
        assertThat(customerSnap(email).inFlight()).isZero();

        reset(customerUserDetailsService);
        assertThat(redirect(customerLogin(email, PW))).isEqualTo("/account");
    }
}
