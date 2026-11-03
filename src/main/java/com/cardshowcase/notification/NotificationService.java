package com.cardshowcase.notification;

import com.cardshowcase.model.entity.NotificationLog;
import com.cardshowcase.model.entity.NotificationStatus;
import com.cardshowcase.model.entity.NotificationType;
import com.cardshowcase.model.entity.Order;
import com.cardshowcase.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records that a business event needs a notification. Runs inside the caller's
 * business transaction (MANDATORY): the row commits atomically with the business
 * state, and if the insert fails the business transaction fails with it.
 * Delivery happens after commit in {@link NotificationDispatcher}.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationLogRepository repository;
    private final NotificationSettings settings;
    private final ApplicationEventPublisher publisher;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Order order, NotificationType type) {
        if (repository.findByOrder_IdAndType(order.getId(), type).isPresent()) {
            return;
        }
        NotificationLog log = repository.save(NotificationLog.builder()
                .order(order)
                .type(type)
                .recipient(recipientFor(order, type))
                .status(NotificationStatus.PENDING)
                .attempts(0)
                .build());
        publisher.publishEvent(new NotificationEnqueuedEvent(log.getId()));
    }

    private String recipientFor(Order order, NotificationType type) {
        if (type == NotificationType.ADMIN_NEW_PAID_ORDER) {
            return settings.getAdminEmail();
        }
        return order.getCustomer() != null ? order.getCustomer().getEmail() : order.getGuestEmail();
    }
}
