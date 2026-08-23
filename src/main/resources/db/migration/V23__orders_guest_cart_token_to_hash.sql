-- Rename guest_cart_token to guest_cart_token_hash.
-- The raw session token is no longer persisted; only its SHA-256 hex digest is
-- stored so that a compromised DB row cannot be used to replay the cookie.
ALTER TABLE orders RENAME COLUMN guest_cart_token TO guest_cart_token_hash;
