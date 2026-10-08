-- Parked carts (held sales) and voids. Every statement is re-runnable. Do not put a semicolon inside a string literal.

-- A held cart keeps the product and quantity of each line. Prices and stock are read again when it is recalled.
CREATE TABLE IF NOT EXISTS held_carts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    label VARCHAR(60),
    user_id BIGINT NOT NULL REFERENCES users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS held_cart_lines (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    held_cart_id BIGINT NOT NULL REFERENCES held_carts (id),
    product_id BIGINT NOT NULL REFERENCES products (id),
    qty INT NOT NULL CHECK (qty > 0),
    discount_bp INT NOT NULL CHECK (discount_bp >= 0 AND discount_bp <= 10000)
);

-- A refund is a return of some items and money; a void reverses a whole sale while its shift is still open.
ALTER TABLE refunds ADD COLUMN IF NOT EXISTS kind VARCHAR(8) DEFAULT 'REFUND' NOT NULL;
ALTER TABLE refunds ADD CONSTRAINT IF NOT EXISTS chk_refunds_kind CHECK (kind IN ('REFUND', 'VOID'));
