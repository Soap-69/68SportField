package com.cardshowcase.repository;

import com.cardshowcase.model.entity.CustomerAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerAddressRepository extends JpaRepository<CustomerAddress, Long> {

    List<CustomerAddress> findByCustomerId(Long customerId);

    Optional<CustomerAddress> findByCustomerIdAndIsDefaultBillingTrue(Long customerId);

    Optional<CustomerAddress> findByCustomerIdAndIsDefaultShippingTrue(Long customerId);
}
