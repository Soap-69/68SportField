-- Week 7: Refund request workflow
CREATE TABLE refund_requests (
    id                      BIGSERIAL    PRIMARY KEY,
    order_id                BIGINT       NOT NULL REFERENCES orders(id),
    requested_amount        NUMERIC(12,2) NOT NULL,
    reason                  TEXT,
    status                  VARCHAR(30)  NOT NULL DEFAULT 'PENDING_APPROVAL',
    requested_by_admin_id   BIGINT       NOT NULL,
    requested_at            TIMESTAMP    NOT NULL,
    reviewed_by_admin_id    BIGINT,
    reviewed_at             TIMESTAMP,
    rejection_reason        TEXT,
    executed_at             TIMESTAMP,
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    version                 BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_refund_requests_order_id ON refund_requests(order_id);
CREATE INDEX idx_refund_requests_status   ON refund_requests(status);
