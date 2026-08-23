-- Stores the guest cart session token on guest orders so that idempotency replay
-- identity can be verified by session (cookie), not by guestEmail.
-- NULL for authenticated customer orders (identity is verified by customer_id).
ALTER TABLE orders ADD COLUMN guest_cart_token VARCHAR(200) NULL;
