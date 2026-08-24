CREATE TABLE payments (
    id                  BIGSERIAL     PRIMARY KEY,
    order_id            BIGINT        NOT NULL UNIQUE REFERENCES orders(id),
    provider            VARCHAR(50)   NOT NULL,
    status              VARCHAR(30)   NOT NULL,
    amount              NUMERIC(10,2) NOT NULL,
    currency            VARCHAR(3)    NOT NULL DEFAULT 'USD',
    idempotency_key     VARCHAR(200)  NOT NULL UNIQUE,
    provider_payment_id VARCHAR(200),
    failure_code        VARCHAR(100),
    failure_message     VARCHAR(500),
    created_at          TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP     NOT NULL DEFAULT NOW()
);
