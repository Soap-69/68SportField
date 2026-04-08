-- =============================================================
-- V3 – Sample product seed data
-- =============================================================

-- ---------------------------------------------------------------
-- Add missing Football L3 "2025" season
-- ---------------------------------------------------------------
INSERT INTO categories (id, name, slug, parent_id, level, sort_order) VALUES
(26, '2025', 'sports-football-2025', 4, 3, 1);

SELECT setval('categories_id_seq', (SELECT MAX(id) FROM categories));

-- ---------------------------------------------------------------
-- Products
--   cat 14 = Sports > Basketball > 2025/26
--   cat 20 = Sports > Baseball > 2025
--   cat 26 = Sports > Football > 2025
--   cat 24 = Entertainment > Pokémon > 2025
-- ---------------------------------------------------------------

INSERT INTO products
  (name, slug, description, highlights, box_break_info, configuration,
   price, original_price, brand,
   is_on_sale, is_new, is_trending, is_best_seller, is_pre_order,
   category_id, sort_order, is_active)
VALUES

-- ── Basketball 1 ────────────────────────────────────────────────
(
  '2025/26 Topps Chrome Basketball Hobby Box',
  '2025-26-topps-chrome-basketball-hobby-box',
  E'The flagship chromium basketball release of the 2025/26 season. Topps Chrome Basketball delivers the crispest Refractor parallels in the hobby, featuring the latest class of NBA rookies alongside established superstars. Every Hobby box guarantees at least one autograph, with a deep rainbow of numbered parallels to chase.',
  E'• 1 guaranteed on-card autograph per box\n• Rookie Refractor short prints and superfractors\n• Numbered parallels: /999, /500, /199, /99, /50, /25, /10, /5, and 1/1\n• Rookie Variation Cards featuring top draft picks\n• Chrome technology for ultra-crisp imaging',
  E'Hobby Box Contents:\n• 12 packs per box\n• 4 cards per pack\n• 1 guaranteed autograph\n• Multiple Refractor parallels per box\n\nNotable Inserts:\n• Rookie Refractor Autographs\n• Chrome Refractor Rainbow\n• Base Variations',
  '12 packs · 4 cards per pack',
  189.99, NULL, 'Topps',
  false, true, true, false, false,
  14, 1, true
),

-- ── Basketball 2 ────────────────────────────────────────────────
(
  '2025/26 Panini Prizm Basketball Hobby Box',
  '2025-26-panini-prizm-basketball-hobby-box',
  E'Panini Prizm Basketball is the most iconic modern basketball card product, celebrated for its bold Prizm parallel technology and stunning photography. The 2025/26 edition features a fresh crop of NBA rookies alongside the biggest stars in the game. Each Hobby box is loaded with silver Prizms and a guaranteed autograph.',
  E'• 1 guaranteed autograph per box\n• Silver Prizm parallels in every pack\n• Exclusive Hobby-only parallel colors\n• Rookie Prizm cards for top 2025 NBA Draft picks\n• Color Prizm parallels: Red, Blue, Gold, Green, Purple, and more',
  E'Hobby Box Contents:\n• 12 packs per box\n• 12 cards per pack\n• 1 guaranteed autograph\n\nParallel Breakdown:\n• Silver Prizm (most common)\n• Red Prizm /299\n• Blue Prizm /199\n• Green Prizm /99\n• Gold Prizm /10\n• Black Prizm 1/1',
  '12 packs · 12 cards per pack',
  229.99, NULL, 'Panini',
  false, false, false, true, false,
  14, 2, true
),

-- ── Basketball 3 (Pre-Order) ─────────────────────────────────────
(
  '2025/26 Panini Select Basketball Hobby Box',
  '2025-26-panini-select-basketball-hobby-box',
  E'Panini Select Basketball is back for another exciting season, offering collectors a unique three-tier structure with Concourse, Premier Level, and Courtside tiers. This pre-order listing secures your allocation of this anticipated release before it sells out.',
  E'• Three-tier base card design: Concourse, Premier Level, and Courtside\n• Multiple exclusive Hobby-only parallels\n• Tie-Dye Prizm #/25 and Gold Prizm #/10\n• Rookie Selections and Downtown inserts\n• Guaranteed autographs and relics per box',
  E'Hobby Box Contents:\n• 8 packs per box\n• 8 cards per pack\n• 2 guaranteed autographs or relics\n\nTiers:\n• Concourse (base)\n• Premier Level (mid)\n• Courtside (top tier)',
  '8 packs · 8 cards per pack',
  199.99, NULL, 'Panini',
  false, false, false, false, true,
  14, 3, true
),

-- ── Baseball 1 ──────────────────────────────────────────────────
(
  '2025 Topps Series 1 Baseball Hobby Box',
  '2025-topps-series-1-baseball-hobby-box',
  E'The tradition starts here. Topps Series 1 Baseball is the cornerstone of the annual baseball card calendar, delivering the first official licensed cards of the newest MLB players. With a clean, classic design and dozens of insert sets, Hobby boxes offer the best per-pack value and exclusive content.',
  E'• First-ever Topps rookie cards for 2025 MLB debuts\n• Hobby-exclusive Silver Pack and Chrome Pack\n• 1 guaranteed relic or autograph per box\n• Iconic Topps insert sets: 1952 Redux, All-Star Stitches, Royal Lineage\n• Black Parallel #/67 and Gold Parallel #/2024',
  E'Hobby Box Contents:\n• 36 packs per box\n• 10 cards per pack\n• 1 Silver Pack (all foil parallels)\n• 1 guaranteed relic or autograph\n\nNotable Inserts:\n• Autograph Relics\n• 1952 Topps Redux\n• Generation Now',
  '36 packs · 10 cards per pack',
  129.99, NULL, 'Topps',
  false, true, false, true, false,
  20, 1, true
),

-- ── Baseball 2 (On Sale) ─────────────────────────────────────────
(
  '2025 Bowman Chrome Baseball Hobby Box',
  '2025-bowman-chrome-baseball-hobby-box',
  E'Bowman Chrome is the most important prospecting product in the baseball hobby. Every year, the first Bowman Chrome appearances of top Minor League prospects become the most sought-after rookie cards. Chrome refractor technology gives every card a stunning, timeless finish.',
  E'• First Bowman Chrome cards for top 2025 MLB Draft picks\n• Prospect Autographs numbered as low as 1/1\n• Scouts Top 100 Prospects insert set\n• Refractor rainbow: Gold /50, Orange /25, Red /5, Super 1/1\n• Farm Team Favorites and Draft Night exclusive inserts',
  E'Hobby Box Contents:\n• 24 packs per box\n• 4 cards per pack\n• 2 guaranteed autographs per box\n\nProspect Autograph Parallels:\n• Gold Refractor /50\n• Orange Refractor /25\n• Red Refractor /5\n• Superfractor 1/1',
  '24 packs · 4 cards per pack',
  99.99, 129.99, 'Topps',
  true, false, false, false, false,
  20, 2, true
),

-- ── Football 1 ──────────────────────────────────────────────────
(
  '2025 Panini Prizm Football Hobby Box',
  '2025-panini-prizm-football-hobby-box',
  E'Panini Prizm Football is the crown jewel of the football card market. Each season brings a new generation of NFL rookies and their highly coveted first Prizm cards. The 2025 edition features the complete class of first-round draft picks and all the brightest stars in the NFL.',
  E'• On-card rookie autographs for top 2025 NFL Draft picks\n• Silver, Gold, Red, Blue, and Green Prizm parallels\n• Hobby-exclusive Emergent and Draft Class insert sets\n• 1 guaranteed Prizm autograph per box\n• Base Prizm Variations featuring action photography',
  E'Hobby Box Contents:\n• 12 packs per box\n• 4 cards per pack\n• 1 guaranteed Prizm autograph\n\nParallel Breakdown:\n• Silver Prizm (base foil)\n• Red Prizm /325\n• Blue Prizm /199\n• Green Prizm /99\n• Gold Prizm /10\n• Black Prizm 1/1',
  '12 packs · 4 cards per pack',
  249.99, NULL, 'Panini',
  false, false, true, false, false,
  26, 1, true
),

-- ── Football 2 (On Sale) ─────────────────────────────────────────
(
  '2025 Panini Donruss Football Retail Blaster Box',
  '2025-panini-donruss-football-retail-blaster-box',
  E'The perfect entry point for football card collectors. Panini Donruss Football offers the classic Donruss design with modern photography and a wide variety of parallels. Blaster boxes provide an affordable way to collect the latest NFL stars and rookie cards.',
  E'• Rated Rookie cards for all top 2025 NFL Draft picks\n• Press Proof parallel exclusive to retail\n• Express Lane and Ritz inserts\n• Donruss ''85 and Retro 1988 throwback designs\n• Great value for set builders and player collectors',
  E'Retail Blaster Box Contents:\n• 11 packs per box\n• 8 cards per pack\n• 1 guaranteed Rated Rookie Prizm\n\nInsert Highlights:\n• Rated Rookies\n• Press Proofs\n• Production Line',
  '11 packs · 8 cards per pack',
  49.99, 64.99, 'Panini',
  true, false, false, false, false,
  26, 2, true
),

-- ── Pokémon 1 ───────────────────────────────────────────────────
(
  'Pokémon Scarlet & Violet — Prismatic Evolutions Booster Box',
  'pokemon-sv-prismatic-evolutions-booster-box',
  E'One of the most celebrated Pokémon TCG sets in recent memory, Prismatic Evolutions brings the beloved Eevee Evolution family to life with stunning Tera Illustration Rare cards and the brand-new Illustration Rare ex mechanics. This set has become a modern hobby cornerstone, with demand far exceeding supply.',
  E'• All 8 Eeveelution Tera ex cards included in the set\n• Tera Illustration Rare cards with full-art prismatic artwork\n• Special Illustration Rares and Hyper Rares\n• New Prismatic Evolution mechanic for the Eevee family\n• Highly sought-after by collectors and competitive players alike',
  E'Booster Box Contents:\n• 36 booster packs per box\n• 10 cards per pack\n• Guaranteed multiple holofoil and rare cards\n\nChase Cards:\n• Tera ex Eeveelutions\n• Full-Art Tera Illustration Rares\n• Special Illustration Rares\n• Hyper Rares (Gold)',
  '36 booster packs per box · 10 cards per pack',
  299.99, NULL, 'The Pokémon Company',
  false, true, true, false, false,
  24, 1, true
),

-- ── Pokémon 2 ───────────────────────────────────────────────────
(
  'Pokémon 151 Ultra Premium Collection',
  'pokemon-151-ultra-premium-collection',
  E'A premium collection celebrating the original 151 Pokémon from the Kanto region. The Pokémon 151 Ultra Premium Collection is a beautiful box set designed for fans of the classics, featuring exclusive oversized cards, a variety of booster packs, and a full array of accessories for competitive play.',
  E'• Exclusive jumbo oversized Mew ex and Mewtwo ex cards\n• 16 Pokémon 151 booster packs included\n• Premium accessories: card sleeves, deck box, and coin\n• Mew ex promo card with alternate gold-bordered artwork\n• Perfect for display and as a collector''s gift',
  E'Ultra Premium Collection Contains:\n• 16 × Pokémon 151 booster packs\n• 1 × Jumbo Mew ex oversized card\n• 1 × Jumbo Mewtwo ex oversized card\n• 1 × Mew ex promo card\n• 65 Pokémon sleeves\n• 1 × Deck Box\n• 1 × Collector''s coin',
  '16 booster packs · Includes accessories',
  119.99, NULL, 'The Pokémon Company',
  false, false, false, true, false,
  24, 2, true
);

SELECT setval('products_id_seq', (SELECT MAX(id) FROM products));
