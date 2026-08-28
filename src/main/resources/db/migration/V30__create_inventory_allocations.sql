CREATE TABLE inventory_allocations (
    id                 BIGSERIAL    PRIMARY KEY,
    order_item_id      BIGINT       NOT NULL REFERENCES order_items(id),
    inventory_id       BIGINT       NOT NULL REFERENCES inventory(id),
    quantity_committed INT          NOT NULL CHECK (quantity_committed > 0),
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_inv_alloc_item_inventory UNIQUE (order_item_id, inventory_id)
);
CREATE INDEX idx_inv_alloc_order_item ON inventory_allocations(order_item_id);
