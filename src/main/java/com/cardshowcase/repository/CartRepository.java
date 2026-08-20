package com.cardshowcase.repository;

import com.cardshowcase.model.entity.Cart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface CartRepository extends JpaRepository<Cart, Long> {
    Optional<Cart> findByCustomerId(Long customerId);
    Optional<Cart> findBySessionToken(String sessionToken);
    void deleteBySessionToken(String sessionToken);

    @Query("SELECT COALESCE(SUM(ci.quantity), 0) FROM CartItem ci WHERE ci.cart.customer.id = :customerId")
    int countItemsByCustomerId(Long customerId);

    @Query("SELECT COALESCE(SUM(ci.quantity), 0) FROM CartItem ci WHERE ci.cart.sessionToken = :sessionToken")
    int countItemsBySessionToken(String sessionToken);
}
