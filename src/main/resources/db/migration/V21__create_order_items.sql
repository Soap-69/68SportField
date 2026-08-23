CREATE TABLE order_items (
    id                     BIGSERIAL     PRIMARY KEY,
    order_id               BIGINT        NOT NULL REFERENCES orders(id),
    product_variant_id     BIGINT        REFERENCES product_variants(id),
    product_name           VARCHAR(300)  NOT NULL,
    sku_snapshot           VARCHAR(100),
    variant_type_snapshot  VARCHAR(50)   NOT NULL,
    unit_price             NUMERIC(10,2) NOT NULL,
    quantity               INT           NOT NULL CHECK (quantity > 0),
    line_subtotal          NUMERIC(10,2) NOT NULL
);
