package com.cardshowcase.notification;

import com.cardshowcase.model.entity.NotificationLog;
import com.cardshowcase.model.entity.NotificationStatus;
import com.cardshowcase.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

import static com.cardshowcase.model.entity.NotificationStatus.*;

/**
 * Every write the dispatcher makes after the business transaction has committed.
 * Each method runs in its own transaction: nothing here depends on, or writes to,
 * the already-committed business transaction.
 */
@Service
@RequiredArgsConstructor
public class NotificationLogWriter {

    static final Set<NotificationStatus> CLAIMABLE = EnumSet.of(PENDING, FAILED, SKIPPED_DISABLED);
    private static final int LAST_ERROR_MAX = 500;

    private final NotificationLogRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(long logId) {
        return repository.claimForSending(logId, SENDING, CLAIMABLE) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markSkippedDisabled(long logId) {
        return repository.markSkippedDisabled(logId, SKIPPED_DISABLED, CLAIMABLE) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(long logId) {
        NotificationLog log = repository.findById(logId).orElseThrow();
        log.setStatus(SENT);
        log.setSentAt(LocalDateTime.now());
        log.setLastError(null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(long logId, String error) {
        NotificationLog log = repository.findById(logId).orElseThrow();
        log.setStatus(FAILED);
        log.setLastError(error.length() > LAST_ERROR_MAX ? error.substring(0, LAST_ERROR_MAX) : error);
    }
}
