-- Purchase orders and goods receipts. Every statement is re-runnable. Do not put a semicolon inside a string literal.

-- An order is a request to a supplier. Its status is worked out from these dates and from what has been received,
-- so it is never stored: no ordered date means a draft, and a cancelled date means cancelled.
CREATE TABLE IF NOT EXISTS purchase_orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    supplier_id BIGINT NOT NULL REFERENCES suppliers (id),
    note VARCHAR(200),
    created_by BIGINT NOT NULL REFERENCES users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ordered_at TIMESTAMP WITH TIME ZONE,
    cancelled_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS purchase_order_lines (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    purchase_order_id BIGINT NOT NULL REFERENCES purchase_orders (id),
    product_id BIGINT NOT NULL REFERENCES products (id),
    qty_ordered INT NOT NULL CHECK (qty_ordered > 0 AND qty_ordered <= 100000),
    unit_cost_cents BIGINT NOT NULL CHECK (unit_cost_cents >= 0)
);

-- A goods receipt is one delivery against an order. Each of its lines adds stock through the stock ledger.
CREATE TABLE IF NOT EXISTS goods_receipts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    purchase_order_id BIGINT NOT NULL REFERENCES purchase_orders (id),
    invoice_no VARCHAR(64),
    received_by BIGINT NOT NULL REFERENCES users (id),
    received_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS goods_receipt_lines (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    goods_receipt_id BIGINT NOT NULL REFERENCES goods_receipts (id),
    purchase_order_line_id BIGINT NOT NULL REFERENCES purchase_order_lines (id),
    qty INT NOT NULL CHECK (qty > 0 AND qty <= 100000),
    unit_cost_cents BIGINT NOT NULL CHECK (unit_cost_cents >= 0)
);

-- A RECEIVE movement made by a delivery points at the receipt that caused it.
ALTER TABLE stock_movements ADD COLUMN IF NOT EXISTS ref_receipt_id BIGINT;
ALTER TABLE stock_movements ADD CONSTRAINT IF NOT EXISTS fk_stock_movements_receipt FOREIGN KEY (ref_receipt_id) REFERENCES goods_receipts (id);

-- How many to order when a product runs low. Zero means "twice the reorder level, less what is on hand".
ALTER TABLE products ADD COLUMN IF NOT EXISTS reorder_qty INT DEFAULT 0 NOT NULL;
ALTER TABLE products ADD CONSTRAINT IF NOT EXISTS chk_products_reorder_qty CHECK (reorder_qty >= 0 AND reorder_qty <= 100000);

CREATE INDEX IF NOT EXISTS idx_purchase_order_lines_order ON purchase_order_lines (purchase_order_id);
CREATE INDEX IF NOT EXISTS idx_goods_receipts_order ON goods_receipts (purchase_order_id);
CREATE INDEX IF NOT EXISTS idx_goods_receipt_lines_order_line ON goods_receipt_lines (purchase_order_line_id);
