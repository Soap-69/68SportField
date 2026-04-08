package com.cardshowcase.controller.api;

import com.cardshowcase.model.dto.InquiryDTO;
import com.cardshowcase.service.InquiryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class InquiryApiController {

    private final InquiryService inquiryService;

    @PostMapping("/inquiry")
    public ResponseEntity<Map<String, Object>> submitInquiry(
            @Valid @RequestBody InquiryDTO dto,
            BindingResult bindingResult) {

        if (bindingResult.hasErrors()) {
            String errors = bindingResult.getFieldErrors().stream()
                    .map(e -> e.getDefaultMessage())
                    .collect(Collectors.joining(", "));
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", errors
            ));
        }

        try {
            inquiryService.createInquiry(dto);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Inquiry submitted! We'll be in touch shortly."
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Failed to submit inquiry. Please try again."
            ));
        }
    }
}
