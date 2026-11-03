package com.cardshowcase.repository;

import com.cardshowcase.model.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    /** Scalar owner projection: identifies the row without materializing its mutable state. */
    interface TokenOwner {
        Long getTokenId();
        Long getCustomerId();
    }

    @Query("select t.id as tokenId, t.customer.id as customerId from PasswordResetToken t where t.token = :token")
    Optional<TokenOwner> findOwnerByToken(@Param("token") String token);

    /** Consumes the presented token and every sibling of the customer in one statement. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PasswordResetToken t set t.used = true where t.customer.id = :customerId and t.used = false")
    int markAllUnusedAsUsed(@Param("customerId") Long customerId);
}
