CREATE TABLE inventory (
    id BIGSERIAL PRIMARY KEY,
    variant_id BIGINT NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
    location_id BIGINT NOT NULL REFERENCES inventory_locations(id),
    quantity INT NOT NULL DEFAULT 0,
    low_stock_threshold INT DEFAULT 5,
    version INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(variant_id, location_id)
);
CREATE INDEX idx_inventory_variant ON inventory(variant_id);
