package com.cardshowcase.repository;

import com.cardshowcase.model.entity.PaymentEvent;
import com.cardshowcase.model.entity.PaymentEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {
    List<PaymentEvent> findByPayment_IdOrderByCreatedAtAsc(Long paymentId);
    boolean existsByPayment_IdAndEventType(Long paymentId, PaymentEventType eventType);
}
