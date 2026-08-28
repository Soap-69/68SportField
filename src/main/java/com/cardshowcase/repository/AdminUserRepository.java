package com.cardshowcase.repository;

import com.cardshowcase.model.entity.AdminUser;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AdminUserRepository extends JpaRepository<AdminUser, Long> {

    Optional<AdminUser> findByUsername(String username);

    List<AdminUser> findAllByOrderByIdAsc();

    long countByRoleAndIsActiveTrue(String role);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AdminUser a WHERE a.role = 'SENIOR_ADMIN' AND a.isActive = true ORDER BY a.id ASC")
    List<AdminUser> findAllEnabledSeniorAdminsForUpdate();
}
