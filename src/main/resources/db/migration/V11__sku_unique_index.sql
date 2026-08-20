-- =============================================================
-- V11 – Enforce global SKU uniqueness on product_variants
-- =============================================================
-- NULL SKUs are allowed (no SKU assigned yet); only non-null
-- SKUs must be globally unique across all variants.
-- =============================================================

-- Drop the old non-unique index created in V6
DROP INDEX IF EXISTS idx_product_variants_sku;

-- Create a partial unique index: enforces uniqueness only where sku IS NOT NULL
CREATE UNIQUE INDEX idx_product_variants_sku_unique
    ON product_variants(sku)
    WHERE sku IS NOT NULL;
