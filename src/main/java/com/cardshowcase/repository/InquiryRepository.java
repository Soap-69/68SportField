package com.cardshowcase.repository;

import com.cardshowcase.model.entity.Inquiry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InquiryRepository extends JpaRepository<Inquiry, Long>,
                                            JpaSpecificationExecutor<Inquiry> {

    List<Inquiry> findByStatus(String status);

    List<Inquiry> findByProductId(Long productId);

    void deleteByProductId(Long productId);

    long countByStatus(String status);

    @Override
    Page<Inquiry> findAll(Specification<Inquiry> spec, Pageable pageable);

    @Query("""
        SELECT i FROM Inquiry i
        LEFT JOIN FETCH i.product p
        LEFT JOIN FETCH p.category c
        LEFT JOIN FETCH c.parent cp
        LEFT JOIN FETCH cp.parent
        WHERE i.id = :id
        """)
    Optional<Inquiry> findByIdWithProduct(@Param("id") Long id);
}
