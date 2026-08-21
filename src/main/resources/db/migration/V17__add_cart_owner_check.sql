-- Enforce that every cart row belongs to exactly one owner:
-- either a logged-in customer OR a guest session token, never both, never neither.
ALTER TABLE carts
    ADD CONSTRAINT chk_carts_owner CHECK (
        (customer_id IS NOT NULL AND session_token IS NULL) OR
        (customer_id IS NULL     AND session_token IS NOT NULL)
    );
