package com.cardshowcase;

import com.cardshowcase.model.entity.AdminUser;
import com.cardshowcase.model.entity.AdminUserAudit;
import com.cardshowcase.model.entity.AdminUserAuditAction;
import com.cardshowcase.repository.AdminUserAuditRepository;
import com.cardshowcase.repository.AdminUserRepository;
import com.cardshowcase.service.AdminUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminUserManagementIntegrationTest extends BaseIntegrationTest {

    @Autowired
    AdminUserRepository adminUserRepository;

    @Autowired
    AdminUserAuditRepository adminUserAuditRepository;

    @Autowired
    AdminUserService adminUserService;

    @SpyBean
    AdminUserAuditRepository spyAuditRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    private AdminUser seniorAdmin;

    @BeforeEach
    void setUp() {
        adminUserAuditRepository.deleteAll();
        adminUserRepository.deleteAll();

        seniorAdmin = adminUserRepository.save(AdminUser.builder()
                .username("senior_test")
                .password(passwordEncoder.encode("adminpass1"))
                .role("SENIOR_ADMIN")
                .isActive(true)
                .build());
    }

    // ── Full lifecycle test ────────────────────────────────────────────────────

    @Test
    void fullCycle_create_roleChange_disable_enable_writesAuditRecords() {
        // 1. Create
        AdminUser created = adminUserService.createAdmin("new_admin", "securepass1", "ADMIN", seniorAdmin.getId());
        assertThat(created.getId()).isNotNull();
        assertThat(created.getRole()).isEqualTo("ADMIN");

        // 2. Change role
        adminUserService.changeRole(created.getId(), "SENIOR_ADMIN", seniorAdmin.getId());

        // 3. Disable — now 2 SENIOR_ADMINs so this is safe
        adminUserService.setEnabled(created.getId(), false, seniorAdmin.getId());

        // 4. Enable
        adminUserService.setEnabled(created.getId(), true, seniorAdmin.getId());

        // Verify 4 audit records exist for this admin
        List<AdminUserAudit> audits = adminUserAuditRepository
                .findByTargetAdminUser_IdOrderByCreatedAtAsc(created.getId());

        assertThat(audits).hasSize(4);
        assertThat(audits.get(0).getAction()).isEqualTo(AdminUserAuditAction.CREATED);
        assertThat(audits.get(1).getAction()).isEqualTo(AdminUserAuditAction.ROLE_CHANGED);
        assertThat(audits.get(2).getAction()).isEqualTo(AdminUserAuditAction.DISABLED);
        assertThat(audits.get(3).getAction()).isEqualTo(AdminUserAuditAction.ENABLED);
    }

    // ── Atomicity test ─────────────────────────────────────────────────────────

    @Test
    void atomicity_mutationAndAuditAreTransactional() {
        // Stub the spy to throw when save() is called on the audit repository
        doThrow(new RuntimeException("Simulated audit write failure"))
                .when(spyAuditRepository).save(any(AdminUserAudit.class));

        // The service call should propagate the RuntimeException (transaction rolls back)
        assertThatThrownBy(() ->
                adminUserService.createAdmin("tx_test_admin", "securepass1", "ADMIN", seniorAdmin.getId())
        ).isInstanceOf(RuntimeException.class);

        // The admin user should NOT have been persisted (transaction rolled back)
        assertThat(adminUserRepository.findByUsername("tx_test_admin")).isEmpty();
    }

    // ── Access control test ────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "regular_admin", roles = {"ADMIN"})
    void regularAdmin_gets403_on_userManagementEndpoints() throws Exception {
        mockMvc.perform(get("/admin/users").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "senior_test", roles = {"SENIOR_ADMIN"})
    void seniorAdmin_canAccess_userManagementPage() throws Exception {
        mockMvc.perform(get("/admin/users").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/users/list"));
    }

    // ── Password encoding test ─────────────────────────────────────────────────

    @Test
    void password_isStoredViaEncoder_neverPlaintext() {
        String rawPassword = "MyRawPassword99";
        AdminUser created = adminUserService.createAdmin("encoded_test", rawPassword, "ADMIN", seniorAdmin.getId());

        AdminUser fromDb = adminUserRepository.findById(created.getId()).orElseThrow();

        // Stored password must not equal raw password
        assertThat(fromDb.getPassword()).isNotEqualTo(rawPassword);
        // Stored password must be verifiable via PasswordEncoder
        assertThat(passwordEncoder.matches(rawPassword, fromDb.getPassword())).isTrue();
    }
}
