-- ============================================================
-- Reset admin credentials to default
-- ============================================================
-- Run this SQL while connected to your card_showcase database.
--
-- Default after reset:  username = admin
--                       password = admin123
--
-- The BCrypt hash below is the same one used in the initial
-- seed migration (V2__seed_data.sql) for 'admin123'.
-- ============================================================

UPDATE admin_users
SET    username = 'admin',
       password = '$2a$10$ZKK9.C8rSdyySP.OCqAQoufV1ZhxsITNoOVd897JT5VXIANvDO9PW'
WHERE  id = 1;

-- IMPORTANT: Log in immediately with admin / admin123
-- and change your password at /admin/settings
