package com.cardshowcase.service;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
public class CustomLoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    private final CartService cartService;

    public CustomLoginSuccessHandler(CartService cartService) {
        this.cartService = cartService;
        setDefaultTargetUrl("/account");
        setAlwaysUseDefaultTargetUrl(false);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws ServletException, IOException {
        String sessionToken = cartService.getCartCookie(request);
        if (sessionToken != null && authentication.getPrincipal() instanceof CustomerPrincipal principal) {
            try {
                cartService.mergeGuestCartOnLogin(sessionToken, principal.getId());
                cartService.deleteCartCookie(response);
            } catch (Exception e) {
                log.warn("Cart merge failed for customer {}: {}", principal.getId(), e.getMessage(), e);
            }
        }
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
