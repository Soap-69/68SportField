package com.cardshowcase.service;

import com.cardshowcase.model.entity.Inquiry;
import org.springframework.data.jpa.domain.Specification;

public class InquirySpecification {

    private InquirySpecification() {}

    public static Specification<Inquiry> hasStatus(String status) {
        if (status == null || status.isBlank()) return null;
        return (root, query, cb) -> cb.equal(root.get("status"), status.toUpperCase());
    }

    public static Specification<Inquiry> hasSearch(String search) {
        if (search == null || search.isBlank()) return null;
        String pattern = "%" + search.toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
            cb.like(cb.lower(root.get("customerName")),  pattern),
            cb.like(cb.lower(root.get("customerEmail")), pattern),
            cb.like(cb.lower(root.get("customerCompany")), pattern)
        );
    }
}
