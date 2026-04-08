package com.cardshowcase.repository;

import com.cardshowcase.model.entity.Banner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BannerRepository extends JpaRepository<Banner, Long> {

    List<Banner> findByIsActiveTrueOrderBySortOrderAsc();

    List<Banner> findAllByOrderBySortOrderAsc();

    long countByIsActiveTrue();
}
