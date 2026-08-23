CREATE TABLE orders (
    id                    BIGSERIAL    PRIMARY KEY,
    order_number          VARCHAR(50)  UNIQUE NOT NULL,
    idempotency_key       VARCHAR(200) UNIQUE NOT NULL,
    request_fingerprint   VARCHAR(64)  NOT NULL,
    customer_id           BIGINT       REFERENCES customers(id),
    status                VARCHAR(30)  NOT NULL,

    -- Guest contact (NULL for logged-in orders)
    guest_name            VARCHAR(200),
    guest_email           VARCHAR(200),

    -- Shipping address snapshot
    shipping_first_name   VARCHAR(100),
    shipping_last_name    VARCHAR(100),
    shipping_address_line1 VARCHAR(300),
    shipping_address_line2 VARCHAR(300),
    shipping_city         VARCHAR(100),
    shipping_state        VARCHAR(50),
    shipping_zip          VARCHAR(20),
    shipping_country      VARCHAR(50),
    shipping_phone        VARCHAR(50),

    -- Billing address snapshot
    billing_first_name    VARCHAR(100),
    billing_last_name     VARCHAR(100),
    billing_address_line1 VARCHAR(300),
    billing_address_line2 VARCHAR(300),
    billing_city          VARCHAR(100),
    billing_state         VARCHAR(50),
    billing_zip           VARCHAR(20),
    billing_country       VARCHAR(50),
    billing_phone         VARCHAR(50),

    -- Financials
    subtotal              NUMERIC(10,2) NOT NULL,
    shipping_amount       NUMERIC(10,2) NOT NULL DEFAULT 0,
    tax_amount            NUMERIC(10,2) NOT NULL DEFAULT 0,
    total                 NUMERIC(10,2) NOT NULL,

    created_at            TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP    NOT NULL DEFAULT NOW()
);
