-- Initial schema. Money is stored as integer cents; tax rates as basis points (1% = 100 bp).
-- Sales, payments, refunds and stock movements are append-only; never UPDATE or DELETE them in code.

CREATE TABLE settings (
    setting_key VARCHAR(64) PRIMARY KEY,
    setting_value VARCHAR(255) NOT NULL
);

INSERT INTO settings (setting_key, setting_value) VALUES
    ('store_name', 'My Store'),
    ('allow_negative_stock', 'false'),
    ('discount_approval_threshold_bp', '1000');

CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(16) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE categories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE suppliers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    contact VARCHAR(255)
);

CREATE TABLE products (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sku VARCHAR(64) NOT NULL UNIQUE,
    barcode VARCHAR(64) UNIQUE,
    name VARCHAR(200) NOT NULL,
    category_id BIGINT REFERENCES categories(id),
    supplier_id BIGINT REFERENCES suppliers(id),
    price_cents BIGINT NOT NULL CHECK (price_cents >= 0),
    cost_cents BIGINT NOT NULL CHECK (cost_cents >= 0),
    tax_rate_bp INT NOT NULL CHECK (tax_rate_bp >= 0 AND tax_rate_bp <= 10000),
    stock_qty INT NOT NULL DEFAULT 0,
    reorder_level INT NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE stock_movements (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES products(id),
    movement_type VARCHAR(16) NOT NULL,
    qty_delta INT NOT NULL,
    reason VARCHAR(255),
    user_id BIGINT NOT NULL REFERENCES users(id),
    ref_sale_id BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE shifts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    opened_at TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at TIMESTAMP WITH TIME ZONE,
    opening_float_cents BIGINT NOT NULL,
    counted_cash_cents BIGINT,
    expected_cash_cents BIGINT
);

CREATE SEQUENCE receipt_seq START WITH 1000;

CREATE TABLE sales (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    receipt_no VARCHAR(20) NOT NULL UNIQUE,
    shift_id BIGINT NOT NULL REFERENCES shifts(id),
    user_id BIGINT NOT NULL REFERENCES users(id),
    discount_approved_by BIGINT REFERENCES users(id),
    subtotal_cents BIGINT NOT NULL,
    discount_cents BIGINT NOT NULL,
    tax_cents BIGINT NOT NULL,
    total_cents BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE sale_lines (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sale_id BIGINT NOT NULL REFERENCES sales(id),
    product_id BIGINT NOT NULL REFERENCES products(id),
    name_snapshot VARCHAR(200) NOT NULL,
    unit_price_cents BIGINT NOT NULL,
    qty INT NOT NULL CHECK (qty > 0),
    discount_bp INT NOT NULL,
    discount_cents BIGINT NOT NULL,
    tax_cents BIGINT NOT NULL,
    line_total_cents BIGINT NOT NULL
);

CREATE TABLE payments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sale_id BIGINT NOT NULL REFERENCES sales(id),
    method VARCHAR(8) NOT NULL,
    applied_cents BIGINT NOT NULL CHECK (applied_cents > 0),
    tendered_cents BIGINT NOT NULL,
    change_cents BIGINT NOT NULL,
    provider_ref VARCHAR(64)
);

CREATE TABLE refunds (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sale_id BIGINT NOT NULL REFERENCES sales(id),
    shift_id BIGINT NOT NULL REFERENCES shifts(id),
    user_id BIGINT NOT NULL REFERENCES users(id),
    approved_by BIGINT NOT NULL REFERENCES users(id),
    method VARCHAR(8) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE refund_lines (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_id BIGINT NOT NULL REFERENCES refunds(id),
    sale_line_id BIGINT NOT NULL REFERENCES sale_lines(id),
    qty INT NOT NULL CHECK (qty > 0),
    amount_cents BIGINT NOT NULL CHECK (amount_cents >= 0)
);

CREATE TABLE audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT REFERENCES users(id),
    action VARCHAR(64) NOT NULL,
    entity VARCHAR(64) NOT NULL,
    entity_id VARCHAR(64),
    detail VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
