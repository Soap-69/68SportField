package com.cardshowcase;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SmokeTest extends BaseIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        assert applicationContext != null;
    }

    @Test
    void publicHomepageAccessible() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk());
    }

    @Test
    void adminRequiresAuth() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void customerLoginPage_accessible() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());
    }

    @Test
    void registerPage_accessible() throws Exception {
        mockMvc.perform(get("/register"))
                .andExpect(status().isOk());
    }

    @Test
    void accountPage_requiresAuth() throws Exception {
        mockMvc.perform(get("/account"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void adminLoginPage_stillWorks() throws Exception {
        mockMvc.perform(get("/admin/login"))
                .andExpect(status().isOk());
    }
}
