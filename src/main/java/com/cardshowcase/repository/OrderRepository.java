package com.cardshowcase.repository;

import com.cardshowcase.model.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    Optional<Order> findByOrderNumber(String orderNumber);

    List<Order> findByCustomer_IdOrderByCreatedAtDesc(Long customerId);
}
