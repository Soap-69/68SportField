package com.cardshowcase.repository;

import com.cardshowcase.model.entity.InventoryLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InventoryLocationRepository extends JpaRepository<InventoryLocation, Long> {

    List<InventoryLocation> findByIsActiveTrue();
}
