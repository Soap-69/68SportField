package com.cardshowcase.controller.admin;

import com.cardshowcase.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/login")
    public String loginPage() {
        return "admin/login";
    }

    @GetMapping({"", "/"})
    public String dashboard(Model model) {
        model.addAttribute("pageTitle", "Dashboard");
        model.addAttribute("totalProducts",   dashboardService.getTotalProducts());
        model.addAttribute("totalCategories", dashboardService.getTotalCategories());
        model.addAttribute("newInquiries",    dashboardService.getNewInquiriesCount());
        model.addAttribute("activeBanners",   dashboardService.getActiveBannersCount());
        return "admin/dashboard";
    }
}
