package com.cardshowcase.repository;

import com.cardshowcase.model.entity.NotificationLog;
import com.cardshowcase.model.entity.NotificationStatus;
import com.cardshowcase.model.entity.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    Optional<NotificationLog> findByOrder_IdAndType(Long orderId, NotificationType type);

    List<NotificationLog> findByOrder_IdOrderByIdAsc(Long orderId);

    /**
     * Atomic claim: exactly one caller can move a claimable row to SENDING.
     * Returns 1 for the winner, 0 for everyone else (already SENDING or SENT).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE NotificationLog n SET n.status = :sending, n.attempts = n.attempts + 1 " +
           "WHERE n.id = :id AND n.status IN :claimable")
    int claimForSending(@Param("id") Long id,
                        @Param("sending") NotificationStatus sending,
                        @Param("claimable") Collection<NotificationStatus> claimable);

    /**
     * Disabled path: records that no delivery was attempted. Does not increment
     * attempts and provides no mutual exclusion (it may legitimately succeed repeatedly).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE NotificationLog n SET n.status = :skipped " +
           "WHERE n.id = :id AND n.status IN :claimable")
    int markSkippedDisabled(@Param("id") Long id,
                            @Param("skipped") NotificationStatus skipped,
                            @Param("claimable") Collection<NotificationStatus> claimable);
}
