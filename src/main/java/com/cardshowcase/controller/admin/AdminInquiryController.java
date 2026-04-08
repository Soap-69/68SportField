package com.cardshowcase.controller.admin;

import com.cardshowcase.model.entity.Inquiry;
import com.cardshowcase.service.InquiryService;
import com.cardshowcase.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin/inquiries")
@RequiredArgsConstructor
public class AdminInquiryController {

    private final InquiryService  inquiryService;
    private final ProductService  productService;

    private static final List<String> STATUSES = List.of("NEW", "IN_PROGRESS", "REPLIED", "CLOSED");

    // ── List ──────────────────────────────────────────────────────────────────

    @GetMapping
    public String list(@RequestParam(required = false) String status,
                       @RequestParam(required = false) String search,
                       @RequestParam(defaultValue = "0")  int page,
                       @RequestParam(defaultValue = "20") int size,
                       Model model) {

        Pageable pageable = PageRequest.of(page, size,
            Sort.by("createdAt").descending());

        Page<Inquiry> inquiries = inquiryService.getInquiries(status, search, pageable);

        Map<String, Long> counts = Map.of(
            "NEW",         inquiryService.getInquiryCountByStatus("NEW"),
            "IN_PROGRESS", inquiryService.getInquiryCountByStatus("IN_PROGRESS"),
            "REPLIED",     inquiryService.getInquiryCountByStatus("REPLIED"),
            "CLOSED",      inquiryService.getInquiryCountByStatus("CLOSED")
        );

        model.addAttribute("pageTitle",  "Inquiry Management");
        model.addAttribute("inquiries",  inquiries);
        model.addAttribute("counts",     counts);
        model.addAttribute("statuses",   STATUSES);
        model.addAttribute("status",     status);
        model.addAttribute("search",     search);
        return "admin/inquiries/list";
    }

    // ── Detail ────────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, Model model) {
        Inquiry inquiry = inquiryService.getInquiryByIdWithProduct(id);

        String primaryImageUrl = null;
        if (inquiry.getProduct() != null) {
            Map<Long, String> imgs = productService.getPrimaryImageUrls(
                List.of(inquiry.getProduct().getId()));
            primaryImageUrl = imgs.get(inquiry.getProduct().getId());
        }

        model.addAttribute("pageTitle",       "Inquiry #" + id);
        model.addAttribute("inquiry",         inquiry);
        model.addAttribute("primaryImageUrl", primaryImageUrl);
        model.addAttribute("statuses",        STATUSES);
        return "admin/inquiries/view";
    }

    // ── Update Status ─────────────────────────────────────────────────────────

    @PostMapping("/{id}/update-status")
    public String updateStatus(@PathVariable Long id,
                               @RequestParam String newStatus,
                               RedirectAttributes flash) {
        try {
            inquiryService.updateStatus(id, newStatus);
            flash.addFlashAttribute("successMessage", "Status updated to " + newStatus + ".");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/inquiries/" + id;
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @RequestParam(defaultValue = "/admin/inquiries") String returnTo,
                         RedirectAttributes flash) {
        try {
            inquiryService.deleteInquiry(id);
            flash.addFlashAttribute("successMessage", "Inquiry deleted successfully.");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:" + returnTo;
    }
}
