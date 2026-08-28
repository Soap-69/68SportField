package com.cardshowcase.service;

import com.cardshowcase.model.entity.AdminUser;
import com.cardshowcase.model.entity.AdminUserAudit;
import com.cardshowcase.model.entity.AdminUserAuditAction;
import com.cardshowcase.repository.AdminUserAuditRepository;
import com.cardshowcase.repository.AdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceUnitTest {

    @Mock
    AdminUserRepository adminUserRepository;

    @Mock
    AdminUserAuditRepository adminUserAuditRepository;

    @Mock
    PasswordEncoder passwordEncoder;

    AdminUserService adminUserService;

    @BeforeEach
    void setUp() {
        adminUserService = new AdminUserService(adminUserRepository, adminUserAuditRepository, passwordEncoder);
    }

    // ── changeRole guards ─────────────────────────────────────────────────────

    @Test
    void changeRole_rejectsSelfModification() {
        Long adminId = 1L;

        // The service checks targetId.equals(actingAdminId) BEFORE any repo lookup
        assertThatThrownBy(() -> adminUserService.changeRole(adminId, "ADMIN", adminId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot change your own role");
    }

    @Test
    void changeRole_rejectsIfWouldLeaveZeroSeniorAdmins() {
        Long targetId = 1L;
        Long actorId  = 2L;

        AdminUser target = AdminUser.builder().id(targetId).username("target").role("SENIOR_ADMIN").isActive(true).build();
        when(adminUserRepository.findById(targetId)).thenReturn(Optional.of(target));
        // Only 1 enabled SENIOR_ADMIN (the target itself)
        when(adminUserRepository.countByRoleAndIsActiveTrue("SENIOR_ADMIN")).thenReturn(1L);

        assertThatThrownBy(() -> adminUserService.changeRole(targetId, "ADMIN", actorId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zero enabled Senior Admin");
    }

    @Test
    void changeRole_succeedsWhenAnotherSeniorAdminExists() {
        Long targetId = 1L;
        Long actorId  = 2L;

        AdminUser target = AdminUser.builder().id(targetId).username("target").role("SENIOR_ADMIN").isActive(true).build();
        AdminUser actor  = AdminUser.builder().id(actorId).username("actor").role("SENIOR_ADMIN").isActive(true).build();
        when(adminUserRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(adminUserRepository.findById(actorId)).thenReturn(Optional.of(actor));
        // 2 enabled SENIOR_ADMINs — demoting one still leaves 1
        when(adminUserRepository.countByRoleAndIsActiveTrue("SENIOR_ADMIN")).thenReturn(2L);
        when(adminUserRepository.save(any(AdminUser.class))).thenAnswer(inv -> inv.getArgument(0));
        when(adminUserAuditRepository.save(any(AdminUserAudit.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminUser result = adminUserService.changeRole(targetId, "ADMIN", actorId);

        assertThat(result.getRole()).isEqualTo("ADMIN");
        verify(adminUserAuditRepository).save(argThat(audit ->
                audit.getAction() == AdminUserAuditAction.ROLE_CHANGED
                && "SENIOR_ADMIN".equals(audit.getOldValue())
                && "ADMIN".equals(audit.getNewValue())));
    }

    // ── setEnabled guards ─────────────────────────────────────────────────────

    @Test
    void setEnabled_rejectsSelfDisable() {
        Long adminId = 1L;

        assertThatThrownBy(() -> adminUserService.setEnabled(adminId, false, adminId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot disable your own account");
    }

    @Test
    void setEnabled_rejectsIfWouldLeaveZeroSeniorAdmins() {
        Long targetId = 1L;
        Long actorId  = 2L;

        AdminUser target = AdminUser.builder().id(targetId).username("target").role("SENIOR_ADMIN").isActive(true).build();
        when(adminUserRepository.findById(targetId)).thenReturn(Optional.of(target));
        // Only 1 enabled SENIOR_ADMIN
        when(adminUserRepository.countByRoleAndIsActiveTrue("SENIOR_ADMIN")).thenReturn(1L);

        assertThatThrownBy(() -> adminUserService.setEnabled(targetId, false, actorId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zero enabled Senior Admin");
    }

    @Test
    void setEnabled_succeedsWhenAnotherSeniorAdminExists() {
        Long targetId = 1L;
        Long actorId  = 2L;

        AdminUser target = AdminUser.builder().id(targetId).username("target").role("SENIOR_ADMIN").isActive(true).build();
        AdminUser actor  = AdminUser.builder().id(actorId).username("actor").role("SENIOR_ADMIN").isActive(true).build();
        when(adminUserRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(adminUserRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(adminUserRepository.countByRoleAndIsActiveTrue("SENIOR_ADMIN")).thenReturn(2L);
        when(adminUserRepository.save(any(AdminUser.class))).thenAnswer(inv -> inv.getArgument(0));
        when(adminUserAuditRepository.save(any(AdminUserAudit.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminUser result = adminUserService.setEnabled(targetId, false, actorId);

        assertThat(result.getIsActive()).isFalse();
        verify(adminUserAuditRepository).save(argThat(audit -> audit.getAction() == AdminUserAuditAction.DISABLED));
    }

    @Test
    void createAdmin_rejectsInvalidRole() {
        assertThatThrownBy(() -> adminUserService.createAdmin("user", "password123", "SUPERUSER", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid role");
    }

    @Test
    void createAdmin_rejectsShortPassword() {
        assertThatThrownBy(() -> adminUserService.createAdmin("user", "short", "ADMIN", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Password must be at least 8 characters");
    }
}
