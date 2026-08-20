package com.cardshowcase.service;

import com.cardshowcase.model.entity.Customer;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Spring Security principal for customer logins.
 * Carries firstName so it is accessible from Thymeleaf via
 * sec:authentication="principal.firstName" without an extra DB call.
 */
@Getter
public class CustomerPrincipal implements UserDetails {

    private final String email;
    private final String passwordHash;
    private final String firstName;
    private final boolean active;

    public CustomerPrincipal(Customer customer) {
        this.email = customer.getEmail();
        this.passwordHash = customer.getPasswordHash();
        this.firstName = customer.getFirstName();
        this.active = Boolean.TRUE.equals(customer.getIsActive());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
