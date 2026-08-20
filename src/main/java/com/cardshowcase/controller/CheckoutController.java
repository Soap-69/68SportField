package com.cardshowcase.controller;

import com.cardshowcase.service.CustomerPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class CheckoutController {

    @GetMapping("/checkout")
    public String checkout(@AuthenticationPrincipal CustomerPrincipal principal) {
        return "checkout";
    }
}
