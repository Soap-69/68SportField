-- =============================================================
-- V31 – notification_log
-- One row per (order, notification type). The row is inserted inside the
-- business transaction that triggers the notification and committed with it.
-- Delivery happens after commit; UNIQUE(order_id, type) guarantees that a
-- business event is recorded once, not that an email is sent once.
-- =============================================================
CREATE TABLE notification_log (
    id          BIGSERIAL    PRIMARY KEY,
    order_id    BIGINT       NOT NULL REFERENCES orders(id),
    type        VARCHAR(40)  NOT NULL,
    recipient   VARCHAR(255) NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    attempts    INT          NOT NULL DEFAULT 0,
    last_error  VARCHAR(500),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    sent_at     TIMESTAMP,
    CONSTRAINT uq_notification_order_type UNIQUE (order_id, type)
);

CREATE INDEX idx_notification_log_order ON notification_log(order_id);
