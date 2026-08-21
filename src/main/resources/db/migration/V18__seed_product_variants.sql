-- Seeds typical purchase variants (Hobby Box / Case / etc.) for all 9 seeded products
-- and creates matching inventory records at MA Warehouse (location_id = 1).
--
-- Using a PL/pgSQL block so we can RETURNING id INTO v_id and chain each
-- variant insert directly to its inventory row without hard-coding IDs.

DO $$
DECLARE
  loc_id BIGINT;
  p1 BIGINT; p2 BIGINT; p3 BIGINT; p4 BIGINT; p5 BIGINT;
  p6 BIGINT; p7 BIGINT; p8 BIGINT; p9 BIGINT;
  v_id BIGINT;
BEGIN

  SELECT id INTO loc_id FROM inventory_locations WHERE name = 'MA Warehouse' LIMIT 1;

  SELECT id INTO p1 FROM products WHERE slug = '2025-26-topps-chrome-basketball-hobby-box';
  SELECT id INTO p2 FROM products WHERE slug = '2025-26-panini-prizm-basketball-hobby-box';
  SELECT id INTO p3 FROM products WHERE slug = '2025-26-panini-select-basketball-hobby-box';
  SELECT id INTO p4 FROM products WHERE slug = '2025-topps-series-1-baseball-hobby-box';
  SELECT id INTO p5 FROM products WHERE slug = '2025-bowman-chrome-baseball-hobby-box';
  SELECT id INTO p6 FROM products WHERE slug = '2025-panini-prizm-football-hobby-box';
  SELECT id INTO p7 FROM products WHERE slug = '2025-panini-donruss-football-retail-blaster-box';
  SELECT id INTO p8 FROM products WHERE slug = 'pokemon-sv-prismatic-evolutions-booster-box';
  SELECT id INTO p9 FROM products WHERE slug = 'pokemon-151-ultra-premium-collection';

  -- ── 2025/26 Topps Chrome Basketball ─────────────────────────────
  INSERT INTO product_variants (product_id, variant_type, sku, price, is_active)
    VALUES (p1, 'Hobby Box', 'TCB-2526-HOB', 189.99, true)
    RETURNING id INTO v_id;
  INSERT INTO inventory (variant_id, location_id, quantity, low_stock_threshold)
    VALUES (v_id, loc_id, 24, 3);

  INSERT INTO product_variants (product_id, variant_type, sku, price, is_active)
    VALUES (p1, 'Case', 'TCB-2526-CASE', 2099.99, true)
    RETURNING id INTO v_id;
  INSERT INTO inventory (variant_id, location_id, quantity, low_stock_threshold)
    VALUES (v_id, loc_id, 4, 1);

  -- ── 2025/26 Panini Prizm Basketball ─────────────────────────────
  INSERT INTO product_variants (product_id, variant_type, sku, price, is_active)
    VALUES (p2, 'Hobby Box', 'PPB-2526-HOB', 229.99, true)
    RETURNING id INTO v_id;
  INSERT INTO inventory (variant_id, location_id, quantity, low_stock_threshold)
    VALUES (v_id, loc_id, 18, 3);

  INSERT INTO product_variants (product_id, variant_type, sku, price, is_active)
    VALUES (p2, 'Case', 'PPB-2526-CASE', 2499.99, true)
    RETURNING id INTO v_id;
  INSERT INTO inventory (variant_id, location_id, quantity, low_stock_threshold)
    VALUES (v_id, loc_id, 3, 1);

  -- ── 2025/26 Panini Select Basketball ────────────────────────────
  INSERT INTO product_variants (product_id, variant_type, sku, price, is_active)
    VALUES (p3, 'Hobby Box', 'PSB-2526-HOB', 199.99, true)
    RETURNING id INTO v_id;
  INSERT INTO inventory (variant_id, location_id, quantity, low_stock_threshold)
    VALUES (v_id, loc_id, 12, 3);

  INSERT INTO product_variants (product_id, variant_type, sku, price, is_active)
    VALUES (p3, 'Case', 'PSB-2526-CASE', 2199.99, true)
    RETURNING id INTO v_id;
  INSERT INTO inventory (variant_id, location_id, quantity, low_stock_threshold)
    VALUES (v_id, loc_id, 2, 1);

  -- ── 2025 Topps Series 1 Baseball ────────────────────────────────
  INSERT INTO product_variants (product_id, variant_type, sku, price, is_active)
    VALUES (p4, 'Hobby Box', 'TS1-25-HOB', 129.99, true)
    RETURNING id INTO v_id;
  INSERT INTO inventory (variant_id, location_id, quantity, low_stock_threshold)
    VALUES (v_id, loc_id, 48, 5);

  INSERT INTO product_variants (product_id, variant_type, sku, price, is_active)
    VALUES (p4, 'Case', 'TS1-25-CASE', 1499.99, true)
    RETURNING id INTO v_id;
  INSERT INTO inventory (variant_id, location_id, quantity, low_stock_threshold)
    VALUES (v_id, loc_id, 6, 1);

  -- ── 2025 Bowman Chrome Baseball (on sale) ───────────────────────
  INSERT INTO product_variants (product_id, variant_type, sku, price, sale_price, is_active)
    VALUES (p5, 'Hobby Box', 'BCB-25-HOB', 129.99, 99.99, true)
    RETURNING id INTO v_id;
  INSERT INTO inventory (variant_id, location_id, quantity, low_stock_threshold)
    VALUES (v_id, loc_id, 36, 5);

  INSERT INTO product_variants (product_id, variant_type, sku, price, sale_price, is_active)
    VALUES (p5, 'Case', 'BCB-25-CASE', 1499.99, 1099.99, true)
    RETURNING id INTO v_id;
  INSERT INTO inventory (variant_id, location_id, quantity, low_stock_threshold)
    VALUES (v_id, loc_id, 4, 1);

  -- ── 2025 Panini Prizm Football ───────────────────────────────────
  INSERT INTO product_variants (product_id, variant_type, sku, price, is_active)
    VALUES (p6, 'Hobby Box', 'PPF-25-HOB', 249.99, true)
    RETURNING id INTO v_id;
  INSERT INTO inventory (variant_id, location_id, quantity, low_stock_threshold)
    VALUES (v_id, loc_id, 20, 3);

  INSERT INTO product_variants (product_id, variant_type, sku, price, is_active)
    VALUES (p6, 'Case', 'PPF-25-CASE', 2799.99, true)
    RETURNING id INTO v_id;
  INSERT INTO inventory (variant_id, location_id, quantity, low_stock_threshold)
    VALUES (v_id, loc_id, 3, 1);

  -- ── 2025 Panini Donruss Football Blaster (on sale, single variant) ──
  INSERT INTO product_variants (product_id, variant_type, sku, price, sale_price, is_active)
    VALUES (p7, 'Blaster Box', 'PDF-25-BLT', 64.99, 49.99, true)
    RETURNING id INTO v_id;
  INSERT INTO inventory (variant_id, location_id, quantity, low_stock_threshold)
    VALUES (v_id, loc_id, 60, 10);

  -- ── Pokémon Prismatic Evolutions ─────────────────────────────────
  INSERT INTO product_variants (product_id, variant_type, sku, price, is_active)
    VALUES (p8, 'Booster Box', 'PKM-PE-BB', 299.99, true)
    RETURNING id INTO v_id;
  INSERT INTO inventory (variant_id, location_id, quantity, low_stock_threshold)
    VALUES (v_id, loc_id, 8, 2);

  -- ── Pokémon 151 Ultra Premium Collection ─────────────────────────
  INSERT INTO product_variants (product_id, variant_type, sku, price, is_active)
    VALUES (p9, 'Collection', 'PKM-151-UPC', 119.99, true)
    RETURNING id INTO v_id;
  INSERT INTO inventory (variant_id, location_id, quantity, low_stock_threshold)
    VALUES (v_id, loc_id, 15, 3);

END $$;
