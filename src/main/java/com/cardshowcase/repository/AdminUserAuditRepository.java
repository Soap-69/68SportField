package com.cardshowcase.repository;

import com.cardshowcase.model.entity.AdminUserAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AdminUserAuditRepository extends JpaRepository<AdminUserAudit, Long> {

    List<AdminUserAudit> findByTargetAdminUser_IdOrderByCreatedAtAsc(Long targetId);
}
