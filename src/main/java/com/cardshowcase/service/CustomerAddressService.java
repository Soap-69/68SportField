package com.cardshowcase.service;

import com.cardshowcase.model.dto.AddressDTO;
import com.cardshowcase.model.entity.Customer;
import com.cardshowcase.model.entity.CustomerAddress;
import com.cardshowcase.repository.CustomerAddressRepository;
import com.cardshowcase.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CustomerAddressService {

    private final CustomerAddressRepository addressRepository;
    private final CustomerRepository customerRepository;

    public List<CustomerAddress> getAddressesByCustomer(Long customerId) {
        return addressRepository.findByCustomerId(customerId);
    }

    public CustomerAddress getAddressById(Long id, Long customerId) {
        CustomerAddress address = addressRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Address not found: " + id));
        if (!address.getCustomer().getId().equals(customerId)) {
            throw new IllegalArgumentException("Address does not belong to this customer.");
        }
        return address;
    }

    @Transactional
    public CustomerAddress createAddress(Long customerId, AddressDTO dto) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + customerId));

        boolean isFirst = addressRepository.findByCustomerId(customerId).isEmpty();

        CustomerAddress address = buildAddress(dto, customer);
        if (isFirst) {
            address.setIsDefaultBilling(true);
            address.setIsDefaultShipping(true);
        }

        return addressRepository.save(address);
    }

    @Transactional
    public CustomerAddress updateAddress(Long id, Long customerId, AddressDTO dto) {
        CustomerAddress address = getAddressById(id, customerId);
        applyDto(address, dto);
        return addressRepository.save(address);
    }

    @Transactional
    public void deleteAddress(Long id, Long customerId) {
        CustomerAddress address = getAddressById(id, customerId);
        addressRepository.delete(address);
    }

    @Transactional
    public void setDefaultBilling(Long addressId, Long customerId) {
        // Clear existing default
        addressRepository.findByCustomerIdAndIsDefaultBillingTrue(customerId)
                .ifPresent(a -> { a.setIsDefaultBilling(false); addressRepository.save(a); });

        CustomerAddress address = getAddressById(addressId, customerId);
        address.setIsDefaultBilling(true);
        addressRepository.save(address);
    }

    @Transactional
    public void setDefaultShipping(Long addressId, Long customerId) {
        // Clear existing default
        addressRepository.findByCustomerIdAndIsDefaultShippingTrue(customerId)
                .ifPresent(a -> { a.setIsDefaultShipping(false); addressRepository.save(a); });

        CustomerAddress address = getAddressById(addressId, customerId);
        address.setIsDefaultShipping(true);
        addressRepository.save(address);
    }

    private CustomerAddress buildAddress(AddressDTO dto, Customer customer) {
        return CustomerAddress.builder()
                .customer(customer)
                .label(dto.getLabel())
                .firstName(dto.getFirstName().trim())
                .lastName(dto.getLastName().trim())
                .company(dto.getCompany())
                .addressLine1(dto.getAddressLine1().trim())
                .addressLine2(dto.getAddressLine2())
                .city(dto.getCity().trim())
                .state(dto.getState())
                .zipCode(dto.getZipCode().trim())
                .country(dto.getCountry() != null ? dto.getCountry() : "US")
                .phone(dto.getPhone())
                .build();
    }

    private void applyDto(CustomerAddress address, AddressDTO dto) {
        address.setLabel(dto.getLabel());
        address.setFirstName(dto.getFirstName().trim());
        address.setLastName(dto.getLastName().trim());
        address.setCompany(dto.getCompany());
        address.setAddressLine1(dto.getAddressLine1().trim());
        address.setAddressLine2(dto.getAddressLine2());
        address.setCity(dto.getCity().trim());
        address.setState(dto.getState());
        address.setZipCode(dto.getZipCode().trim());
        address.setCountry(dto.getCountry() != null ? dto.getCountry() : "US");
        address.setPhone(dto.getPhone());
    }
}
