CREATE TABLE payment_events (
    id          BIGSERIAL    PRIMARY KEY,
    payment_id  BIGINT       NOT NULL REFERENCES payments(id),
    event_id    VARCHAR(36)  NOT NULL UNIQUE,
    event_type  VARCHAR(50)  NOT NULL,
    provider    VARCHAR(50)  NOT NULL,
    metadata    VARCHAR(1000),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_payment_events_payment_id ON payment_events(payment_id);
