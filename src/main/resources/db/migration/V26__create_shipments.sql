-- Week 6: Shipment entity — one per order, parallel to the payments table.
-- service_level is nullable: for AK/HI orders the Shipment is created at PAID
-- transition time when the carrier service level is not yet determined.
CREATE TABLE shipments (
    id                      BIGSERIAL    PRIMARY KEY,
    order_id                BIGINT       NOT NULL UNIQUE REFERENCES orders(id) ON DELETE CASCADE,
    carrier                 VARCHAR(50),
    service_level           VARCHAR(30),
    quoted_shipping_amount  NUMERIC(10,2),
    shipping_payment_status VARCHAR(30)  NOT NULL,
    tracking_number         VARCHAR(200),
    quoted_at               TIMESTAMP,
    shipping_paid_at        TIMESTAMP,
    shipped_at              TIMESTAMP,
    delivered_at            TIMESTAMP,
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP    NOT NULL DEFAULT NOW()
);
