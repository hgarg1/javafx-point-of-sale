-- Hardening: indexes for reports and lookups, integrity checks, bookkeeping columns and normalised data.
-- Every statement is re-runnable (IF NOT EXISTS), so an upgrade retried after a failure does not stumble over
-- objects it already created. Do not put a semicolon inside a string literal in this file.

CREATE INDEX IF NOT EXISTS idx_sales_created ON sales (created_at);
CREATE INDEX IF NOT EXISTS idx_refunds_created ON refunds (created_at);
CREATE INDEX IF NOT EXISTS idx_refunds_sale ON refunds (sale_id);
CREATE INDEX IF NOT EXISTS idx_movements_product_created ON stock_movements (product_id, created_at);
CREATE INDEX IF NOT EXISTS idx_payments_sale ON payments (sale_id);
CREATE INDEX IF NOT EXISTS idx_sale_lines_sale ON sale_lines (sale_id);
CREATE INDEX IF NOT EXISTS idx_refund_lines_sale_line ON refund_lines (sale_line_id);
CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_log (created_at);

ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE shifts ADD COLUMN IF NOT EXISTS closed_by BIGINT REFERENCES users (id);
ALTER TABLE sales ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64) UNIQUE;
ALTER TABLE refunds ADD COLUMN IF NOT EXISTS provider_ref VARCHAR(64);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS status VARCHAR(16) DEFAULT 'CAPTURED' NOT NULL;

ALTER TABLE users ADD CONSTRAINT IF NOT EXISTS chk_users_role CHECK (role IN ('CASHIER', 'MANAGER', 'ADMIN'));
ALTER TABLE stock_movements ADD CONSTRAINT IF NOT EXISTS chk_movement_type
    CHECK (movement_type IN ('RECEIVE', 'SALE', 'REFUND_RETURN', 'ADJUST', 'WASTE'));

-- Normalise existing rows to the rules the app now enforces: usernames and SKUs are case-insensitive.
UPDATE users SET username = LOWER(username);
UPDATE products SET sku = UPPER(sku);
