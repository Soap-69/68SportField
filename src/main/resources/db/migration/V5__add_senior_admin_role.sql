-- =============================================================
-- V5 – Add SENIOR_ADMIN role support to admin_users
-- =============================================================
-- The role column already exists from V1, but this migration
-- ensures any NULL values are set and documents the new role value.
-- =============================================================

ALTER TABLE admin_users ADD COLUMN IF NOT EXISTS role VARCHAR(20) DEFAULT 'SENIOR_ADMIN';
UPDATE admin_users SET role = 'SENIOR_ADMIN' WHERE role IS NULL;
