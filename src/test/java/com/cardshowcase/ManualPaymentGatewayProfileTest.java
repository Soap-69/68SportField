package com.cardshowcase;

import com.cardshowcase.payment.ManualPaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies that the ManualPaymentGateway bean and its controller endpoint are
 * absent/unreachable when the "dev" and "test" profiles are not active.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testprod-pay;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.flyway.enabled=false",
    "app.upload-dir=./test-uploads",
    "app.notification.admin-email=test@test.com",
    "app.notification.enabled=false",
    "spring.mail.host=localhost",
    "spring.mail.port=25"
})
class ManualPaymentGatewayProfileTest {

    @Autowired ApplicationContext context;
    @Autowired MockMvc mockMvc;

    @Test
    void manualPaymentGateway_beanAbsentInProdProfile() {
        String[] beans = context.getBeanNamesForType(ManualPaymentGateway.class);
        assertThat(beans).isEmpty();
    }

    @Test
    void manualPaymentEndpoint_absentInProdProfile_returns404() throws Exception {
        mockMvc.perform(post("/admin/api/orders/1/payments/manual-confirm")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"SUCCESS\"}"))
                .andExpect(status().isNotFound());
    }
}
