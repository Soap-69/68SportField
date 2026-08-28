package com.cardshowcase.controller.admin;

import com.cardshowcase.model.entity.Order;
import com.cardshowcase.model.entity.OrderStatus;
import com.cardshowcase.model.entity.RefundRequest;
import com.cardshowcase.model.entity.RefundRequestStatus;
import com.cardshowcase.model.entity.Shipment;
import com.cardshowcase.model.entity.ShippingPaymentStatus;
import com.cardshowcase.service.OrderService;
import com.cardshowcase.service.PaymentService;
import com.cardshowcase.service.RefundService;
import com.cardshowcase.service.ShippingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final OrderService orderService;
    private final PaymentService paymentService;
    private final ShippingService shippingService;
    private final RefundService refundService;

    @GetMapping
    public String list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model,
            HttpServletRequest request) {

        Pageable pageable = PageRequest.of(page, size);
        Page<Order> orders = orderService.findOrders(search, status, from, to, pageable);

        model.addAttribute("orders", orders);
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("orderStatuses", OrderStatus.values());
        model.addAttribute("pageTitle", "Orders");
        model.addAttribute("currentUri", request.getRequestURI());
        return "admin/orders/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model, HttpServletRequest request) {
        Order order = orderService.findById(id);
        var payment = paymentService.findByOrderId(id).orElse(null);
        Shipment shipment = shippingService.findByOrderId(id).orElse(null);
        List<RefundRequest> refundRequests = refundService.findByOrderId(id);

        boolean hasExecutedRefund = refundRequests.stream()
                .anyMatch(r -> r.getStatus() == RefundRequestStatus.EXECUTED);

        boolean hasSupplementalShipping = shipment != null
                && shipment.getShippingPaymentStatus() == ShippingPaymentStatus.PAID
                && shipment.getQuotedShippingAmount() != null;

        model.addAttribute("order", order);
        model.addAttribute("payment", payment);
        model.addAttribute("shipment", shipment);
        model.addAttribute("refundRequests", refundRequests);
        model.addAttribute("hasExecutedRefund", hasExecutedRefund);
        model.addAttribute("hasSupplementalShipping", hasSupplementalShipping);
        model.addAttribute("pageTitle", "Order " + order.getOrderNumber());
        model.addAttribute("currentUri", request.getRequestURI());
        return "admin/orders/detail";
    }
}
