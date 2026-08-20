CREATE TABLE suppliers (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    website VARCHAR(500),
    contact_email VARCHAR(200),
    notes TEXT,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE supplier_products (
    id BIGSERIAL PRIMARY KEY,
    supplier_id BIGINT NOT NULL REFERENCES suppliers(id),
    variant_id BIGINT REFERENCES product_variants(id),
    external_product_id VARCHAR(200),
    supplier_sku VARCHAR(200),
    source_url VARCHAR(500),
    supplier_cost DECIMAL(10,2),
    availability_status VARCHAR(50),
    last_checked_at TIMESTAMP,
    manual_override BOOLEAN DEFAULT false,
    notes TEXT,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_supplier_products_variant ON supplier_products(variant_id);
CREATE INDEX idx_supplier_products_supplier ON supplier_products(supplier_id);
