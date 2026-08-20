CREATE TABLE cart_items (
    id BIGSERIAL PRIMARY KEY,
    cart_id BIGINT NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
    variant_id BIGINT NOT NULL REFERENCES product_variants(id),
    quantity INT NOT NULL DEFAULT 1,
    added_at TIMESTAMP DEFAULT NOW(),
    CONSTRAINT uq_cart_items_variant UNIQUE(cart_id, variant_id)
);
CREATE INDEX idx_cart_items_cart ON cart_items(cart_id);
