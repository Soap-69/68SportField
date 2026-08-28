package com.cardshowcase.controller.admin;

import com.cardshowcase.model.entity.Order;
import com.cardshowcase.model.entity.Shipment;
import com.cardshowcase.repository.ShipmentRepository;
import com.cardshowcase.service.OrderService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Slf4j
@Controller
@RequiredArgsConstructor
public class AdminOrderExportController {

    private final OrderService orderService;
    private final ShipmentRepository shipmentRepository;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @GetMapping("/admin/api/orders/export")
    public void export(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletResponse response) throws IOException {

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"orders.csv\"");

        List<Order> orders = orderService.findOrdersForExport(search, status, from, to);

        try (PrintWriter writer = response.getWriter()) {
            // Header row — allowlisted columns only
            writer.println("order_number,customer_or_guest_name,email,status,subtotal," +
                           "shipping_amount,tax_amount,total,created_at,shipping_state," +
                           "carrier,tracking_number");

            for (Order order : orders) {
                // customer_or_guest_name
                String name;
                if (order.getGuestName() != null) {
                    name = order.getGuestName();
                } else if (order.getCustomer() != null) {
                    name = nvl(order.getShippingFirstName()) + " " + nvl(order.getShippingLastName());
                } else {
                    name = nvl(order.getShippingFirstName()) + " " + nvl(order.getShippingLastName());
                }

                // email
                String email;
                if (order.getGuestEmail() != null) {
                    email = order.getGuestEmail();
                } else if (order.getCustomer() != null) {
                    try {
                        email = order.getCustomer().getEmail();
                    } catch (Exception e) {
                        email = "";
                    }
                } else {
                    email = "";
                }

                // carrier and tracking from shipment
                Optional<Shipment> shipmentOpt = shipmentRepository.findByOrder_Id(order.getId());
                String carrier = shipmentOpt.map(s -> nvl(s.getCarrier())).orElse("");
                String trackingNumber = shipmentOpt.map(s -> nvl(s.getTrackingNumber())).orElse("");

                writer.println(String.join(",",
                    csvEscape(order.getOrderNumber()),
                    csvEscape(name.trim()),
                    csvEscape(email),
                    csvEscape(order.getStatus().name()),
                    order.getSubtotal().toPlainString(),
                    order.getShippingAmount().toPlainString(),
                    order.getTaxAmount().toPlainString(),
                    order.getTotal().toPlainString(),
                    order.getCreatedAt() != null ? csvEscape(order.getCreatedAt().format(DT_FMT)) : "",
                    csvEscape(nvl(order.getShippingState())),
                    csvEscape(carrier),
                    csvEscape(trackingNumber)
                ));
            }
        }
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }

    private static String csvEscape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
