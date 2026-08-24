package com.cardshowcase.service;

import com.cardshowcase.model.dto.RegisterDTO;
import com.cardshowcase.model.entity.Customer;
import com.cardshowcase.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for CustomerAuthService using H2 in-memory database.
 * @BeforeEach handles isolation via deleteAll(); @DirtiesContext is not used
 * to avoid tearing down the shared H2 schema between test contexts.
 */
@SpringBootTest
@ActiveProfiles("test")
class CustomerAuthServiceTest {

    @Autowired private CustomerAuthService customerAuthService;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private CartItemRepository cartItemRepository;
    @Autowired private CartRepository cartRepository;
    @Autowired private CustomerAddressRepository addressRepository;
    @Autowired private PaymentEventRepository paymentEventRepository;
    @Autowired private PaymentRepository paymentRepository;

    private RegisterDTO validDto() {
        RegisterDTO dto = new RegisterDTO();
        dto.setEmail("test@example.com");
        dto.setPassword("Password1");
        dto.setConfirmPassword("Password1");
        dto.setFirstName("Jane");
        dto.setLastName("Smith");
        dto.setPhone(null);
        return dto;
    }

    @BeforeEach
    void cleanUp() {
        // Delete in FK dependency order so customer deleteAll doesn't hit referential constraints
        orderItemRepository.deleteAll();
        paymentEventRepository.deleteAll();
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        addressRepository.deleteAll();
        customerRepository.deleteAll();
    }

    @Test
    void register_success() {
        RegisterDTO dto = validDto();
        Customer customer = customerAuthService.register(dto);

        assertThat(customer.getId()).isNotNull();
        assertThat(customer.getEmail()).isEqualTo("test@example.com");
        assertThat(customer.getFirstName()).isEqualTo("Jane");
        assertThat(customer.getLastName()).isEqualTo("Smith");
        // Password must be stored as BCrypt hash, not plaintext
        assertThat(customer.getPasswordHash()).isNotEqualTo("Password1");
        assertThat(customer.getPasswordHash()).startsWith("$2a$");
    }

    @Test
    void register_duplicateEmail_throwsException() {
        customerAuthService.register(validDto());

        RegisterDTO dto2 = validDto();
        dto2.setEmail("TEST@EXAMPLE.COM"); // uppercase — should still be caught

        assertThatThrownBy(() -> customerAuthService.register(dto2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void register_passwordMismatch_throwsException() {
        RegisterDTO dto = validDto();
        dto.setConfirmPassword("DifferentPass1");

        assertThatThrownBy(() -> customerAuthService.register(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("match");
    }
}
