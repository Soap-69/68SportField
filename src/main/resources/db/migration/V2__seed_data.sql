-- =============================================================
-- V2 – Seed data
-- =============================================================

-- ---------------------------------------------------------------
-- Admin user  (plaintext: admin123)
-- ---------------------------------------------------------------
INSERT INTO admin_users (username, password, role, is_active) VALUES
('admin', '$2a$10$ZKK9.C8rSdyySP.OCqAQoufV1ZhxsITNoOVd897JT5VXIANvDO9PW', 'ADMIN', true);

-- ---------------------------------------------------------------
-- Categories
-- L1: Sports, Entertainment
-- L2: Sports → Baseball, Football, Basketball, Hockey, Soccer, Other Sports
--     Entertainment → Pokémon, One Piece, Marvel, Disney, Star Wars
-- L3: Basketball → season years
--     Baseball    → year seasons
--     Pokémon     → year seasons
-- ---------------------------------------------------------------

-- L1
INSERT INTO categories (id, name, slug, parent_id, level, sort_order) VALUES
(1,  'Sports',        'sports',        NULL, 1, 1),
(2,  'Entertainment', 'entertainment', NULL, 1, 2);

-- L2 – Sports
INSERT INTO categories (id, name, slug, parent_id, level, sort_order) VALUES
(3,  'Baseball',     'sports-baseball',     1, 2, 1),
(4,  'Football',     'sports-football',     1, 2, 2),
(5,  'Basketball',   'sports-basketball',   1, 2, 3),
(6,  'Hockey',       'sports-hockey',       1, 2, 4),
(7,  'Soccer',       'sports-soccer',       1, 2, 5),
(8,  'Other Sports', 'sports-other',        1, 2, 6);

-- L2 – Entertainment
INSERT INTO categories (id, name, slug, parent_id, level, sort_order) VALUES
(9,  'Pokémon',   'entertainment-pokemon',    2, 2, 1),
(10, 'One Piece', 'entertainment-one-piece',  2, 2, 2),
(11, 'Marvel',    'entertainment-marvel',     2, 2, 3),
(12, 'Disney',    'entertainment-disney',     2, 2, 4),
(13, 'Star Wars', 'entertainment-star-wars',  2, 2, 5);

-- L3 – Basketball seasons
INSERT INTO categories (id, name, slug, parent_id, level, sort_order) VALUES
(14, '2025/26', 'sports-basketball-2025-26', 5, 3, 1),
(15, '2024/25', 'sports-basketball-2024-25', 5, 3, 2),
(16, '2023/24', 'sports-basketball-2023-24', 5, 3, 3),
(17, '2022/23', 'sports-basketball-2022-23', 5, 3, 4),
(18, '2021/22', 'sports-basketball-2021-22', 5, 3, 5);

-- L3 – Baseball seasons
INSERT INTO categories (id, name, slug, parent_id, level, sort_order) VALUES
(19, '2026', 'sports-baseball-2026', 3, 3, 1),
(20, '2025', 'sports-baseball-2025', 3, 3, 2),
(21, '2024', 'sports-baseball-2024', 3, 3, 3),
(22, '2023', 'sports-baseball-2023', 3, 3, 4);

-- L3 – Pokémon seasons
INSERT INTO categories (id, name, slug, parent_id, level, sort_order) VALUES
(23, '2026', 'entertainment-pokemon-2026', 9, 3, 1),
(24, '2025', 'entertainment-pokemon-2025', 9, 3, 2),
(25, '2024', 'entertainment-pokemon-2024', 9, 3, 3);

-- Reset sequence so next auto-generated id starts after our explicit inserts
SELECT setval('categories_id_seq', (SELECT MAX(id) FROM categories));
