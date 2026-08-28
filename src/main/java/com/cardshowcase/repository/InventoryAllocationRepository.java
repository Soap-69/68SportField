package com.cardshowcase.repository;

import com.cardshowcase.model.entity.InventoryAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface InventoryAllocationRepository extends JpaRepository<InventoryAllocation, Long> {
    @Query("SELECT a FROM InventoryAllocation a JOIN FETCH a.inventory WHERE a.orderItem.order.id = :orderId")
    List<InventoryAllocation> findByOrderId(@Param("orderId") Long orderId);
}
