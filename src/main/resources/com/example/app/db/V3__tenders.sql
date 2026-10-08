-- Payment tenders become configurable, and gift cards and store credit get one append-only ledger.
-- Every statement is re-runnable (IF NOT EXISTS / MERGE / same-type ALTER). Do not put a semicolon inside a string
-- literal in this file.

-- Widen the tender columns: EXTERNAL and STORED_VALUE are longer than the original 8 characters.
ALTER TABLE payments ALTER COLUMN method SET DATA TYPE VARCHAR(16);
ALTER TABLE refunds ALTER COLUMN method SET DATA TYPE VARCHAR(16);

-- The tenders the till offers: name, whether offered, order on screen, whether cash taken in it belongs in the drawer,
-- and whether the cashier must enter a reference (a wallet transaction id, a gift card code).
CREATE TABLE IF NOT EXISTS tenders (
    code VARCHAR(16) PRIMARY KEY,
    name VARCHAR(40) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL,
    counts_in_drawer BOOLEAN NOT NULL DEFAULT FALSE,
    requires_reference BOOLEAN NOT NULL DEFAULT FALSE
);

MERGE INTO tenders (code, name, enabled, sort_order, counts_in_drawer, requires_reference) KEY (code)
    VALUES ('CASH', 'Cash', TRUE, 1, TRUE, FALSE);
MERGE INTO tenders (code, name, enabled, sort_order, counts_in_drawer, requires_reference) KEY (code)
    VALUES ('CARD', 'Card', TRUE, 2, FALSE, FALSE);
MERGE INTO tenders (code, name, enabled, sort_order, counts_in_drawer, requires_reference) KEY (code)
    VALUES ('STORED_VALUE', 'Gift card / store credit', TRUE, 3, FALSE, TRUE);
MERGE INTO tenders (code, name, enabled, sort_order, counts_in_drawer, requires_reference) KEY (code)
    VALUES ('EXTERNAL', 'QR / wallet', TRUE, 4, FALSE, TRUE);

-- One ledger for gift cards and store credit. A card's balance is the sum of its entries, so nothing is ever edited.
-- Amounts are signed: ISSUE and REFUND_CREDIT add value, REDEEM takes it away.
CREATE TABLE IF NOT EXISTS stored_value_entries (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_code VARCHAR(32) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    amount_cents BIGINT NOT NULL,
    sale_id BIGINT REFERENCES sales (id),
    refund_id BIGINT REFERENCES refunds (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_stored_value_kind CHECK (kind IN ('ISSUE', 'REDEEM', 'REFUND_CREDIT', 'ADJUST'))
);
CREATE INDEX IF NOT EXISTS idx_stored_value_account ON stored_value_entries (account_code);

-- Products are either stock (tracked in quantities) or gift cards (sold at face value, untaxed, no stock).
ALTER TABLE products ADD COLUMN IF NOT EXISTS kind VARCHAR(16) DEFAULT 'STOCK' NOT NULL;
ALTER TABLE products ADD CONSTRAINT IF NOT EXISTS chk_products_kind CHECK (kind IN ('STOCK', 'GIFT_CARD'));
