-- Adds the created_at column that AdminUser.@PrePersist populates on insert.
-- Nullable so pre-existing rows are not given a misleading timestamp.
ALTER TABLE admin_users
    ADD COLUMN created_at TIMESTAMP NULL;
