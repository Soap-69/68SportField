package com.cardshowcase;

import com.cardshowcase.model.dto.RegisterDTO;
import com.cardshowcase.model.entity.AdminUser;
import com.cardshowcase.model.entity.Customer;
import com.cardshowcase.repository.AdminUserRepository;
import com.cardshowcase.service.CustomerAuthService;
import com.cardshowcase.service.CustomerUserDetailsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T0a–T0d: each chain authenticates only its own realm through an explicit no-parent
 * ProviderManager. Before Week 9 the auto-configured global manager (built from the single
 * UserDetailsService bean, i.e. customers) was the parent of both chains.
 */
class AuthenticationWiringTest extends BaseIntegrationTest {

    @Autowired CustomerAuthService customerAuthService;
    @Autowired AdminUserRepository adminUserRepository;
    @Autowired BCryptPasswordEncoder passwordEncoder;

    @MockitoSpyBean CustomerUserDetailsService customerUserDetailsService;
    @MockitoSpyBean(name = "customerAuthenticationProvider") DaoAuthenticationProvider customerAuthenticationProvider;
    @MockitoSpyBean(name = "adminAuthenticationProvider") DaoAuthenticationProvider adminAuthenticationProvider;

    private final long ts = System.nanoTime();
    private String customerEmail;
    private String adminName;

    @BeforeEach
    void fixtures() {
        RegisterDTO dto = new RegisterDTO();
        customerEmail = "wiring-" + ts + "@w9.test";
        dto.setEmail(customerEmail);
        dto.setPassword("Password1");
        dto.setConfirmPassword("Password1");
        dto.setFirstName("Wire");
        dto.setLastName("Test");
        Customer c = customerAuthService.register(dto);
        assertThat(c.getId()).isNotNull();

        adminName = "wiring-admin-" + ts;
        adminUserRepository.save(AdminUser.builder()
                .username(adminName).password(passwordEncoder.encode("Password1")).role("ADMIN").build());
        clearInvocations(customerUserDetailsService, customerAuthenticationProvider, adminAuthenticationProvider);
    }

    @Test
    void t0a_customerCredentialsOnAdminForm_areRejected_andMintNoSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult result = mockMvc.perform(post("/admin/login").with(csrf()).session(session)
                        .param("username", customerEmail).param("password", "Password1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login?error"))
                .andReturn();
        assertThat(result.getRequest().getSession(false)
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
        verify(customerAuthenticationProvider, never()).authenticate(any());
        verify(customerUserDetailsService, never()).loadUserByUsername(any());
        verify(adminAuthenticationProvider, times(1)).authenticate(any());
    }

    @Test
    void t0b_adminCredentialsOnCustomerForm_areRejected() throws Exception {
        mockMvc.perform(post("/login").with(csrf())
                        .param("username", adminName).param("password", "Password1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
        verify(adminAuthenticationProvider, never()).authenticate(any());
        verify(customerAuthenticationProvider, times(1)).authenticate(any());
    }

    @Test
    void t0c_failedAdminLogin_performsNoCustomerLookup() throws Exception {
        mockMvc.perform(post("/admin/login").with(csrf())
                        .param("username", adminName).param("password", "wrong-pass-1"))
                .andExpect(redirectedUrl("/admin/login?error"));
        verify(customerUserDetailsService, never()).loadUserByUsername(any());
        verify(customerAuthenticationProvider, never()).authenticate(any());
        verify(adminAuthenticationProvider, times(1)).authenticate(any());
    }

    @Test
    void t0d_failedCustomerLogin_invokesTheCustomerDelegateExactlyOnce() throws Exception {
        mockMvc.perform(post("/login").with(csrf())
                        .param("username", customerEmail).param("password", "wrong-pass-1"))
                .andExpect(redirectedUrl("/login?error"));
        verify(customerAuthenticationProvider, times(1)).authenticate(any());
        verify(customerUserDetailsService, times(1)).loadUserByUsername(customerEmail);
        verify(adminAuthenticationProvider, never()).authenticate(any());
    }

    @Test
    void customerSessionStillCannotReachAdminEndpoints() throws Exception {
        MvcResult login = mockMvc.perform(post("/login").with(csrf())
                        .param("username", customerEmail).param("password", "Password1"))
                .andExpect(redirectedUrl("/account"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        mockMvc.perform(get("/admin").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/orders").session(session)).andExpect(status().isForbidden());
    }
}
