package com.cardshowcase.service;

import com.cardshowcase.model.dto.InquiryDTO;
import com.cardshowcase.model.entity.Inquiry;
import com.cardshowcase.model.entity.Product;
import com.cardshowcase.repository.InquiryRepository;
import com.cardshowcase.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InquiryService {

    private final InquiryRepository inquiryRepository;
    private final ProductRepository productRepository;
    private final EmailService emailService;

    @Transactional(readOnly = true)
    public Page<Inquiry> getInquiries(String status, String search, Pageable pageable) {
        Specification<Inquiry> spec = Specification
            .where(InquirySpecification.hasStatus(status))
            .and(InquirySpecification.hasSearch(search));
        return inquiryRepository.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public Inquiry getInquiryById(Long id) {
        return inquiryRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Inquiry not found: " + id));
    }

    @Transactional(readOnly = true)
    public Inquiry getInquiryByIdWithProduct(Long id) {
        return inquiryRepository.findByIdWithProduct(id)
            .orElseThrow(() -> new IllegalArgumentException("Inquiry not found: " + id));
    }

    @Transactional(readOnly = true)
    public long getInquiryCountByStatus(String status) {
        return inquiryRepository.countByStatus(status);
    }

    @Transactional
    public void updateStatus(Long id, String newStatus) {
        Inquiry inquiry = getInquiryById(id);
        inquiry.setStatus(newStatus.toUpperCase());
        inquiryRepository.save(inquiry);
    }

    @Transactional
    public void deleteInquiry(Long id) {
        Inquiry inquiry = getInquiryById(id);
        inquiryRepository.delete(inquiry);
    }

    @Transactional
    public Inquiry createInquiry(InquiryDTO dto) {
        Product product = null;
        if (dto.getProductId() != null) {
            product = productRepository.findById(dto.getProductId()).orElse(null);
        }
        Inquiry inquiry = Inquiry.builder()
            .product(product)
            .customerName(dto.getCustomerName())
            .customerEmail(dto.getCustomerEmail())
            .customerPhone(dto.getCustomerPhone())
            .customerCompany(dto.getCustomerCompany())
            .quantity(dto.getQuantity())
            .message(dto.getMessage())
            .status("NEW")
            .build();
        Inquiry saved = inquiryRepository.save(inquiry);
        emailService.sendNewInquiryNotification(saved);
        return saved;
    }
}
