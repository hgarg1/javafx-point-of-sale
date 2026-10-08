-- Tender types become configurable. Each tender has a kind, which decides how it behaves, and payments and refunds keep a
-- copy of the tender name as it was when the money moved. Every statement is re-runnable. Do not put a semicolon inside a
-- string literal in this file.

-- The kind decides the behaviour: cash goes in the drawer and gives change, the other kinds need a reference. The built-in
-- tenders were seeded with their kind as their code, so for them the code is the kind.
ALTER TABLE tenders ADD COLUMN IF NOT EXISTS kind VARCHAR(16);
UPDATE tenders SET kind = code WHERE kind IS NULL;
ALTER TABLE tenders ALTER COLUMN kind SET NOT NULL;
ALTER TABLE tenders ADD CONSTRAINT IF NOT EXISTS chk_tenders_kind CHECK (kind IN ('CASH', 'CARD', 'EXTERNAL', 'STORED_VALUE'));

-- Every tender except cash needs a reference: the card number, the wallet or voucher reference, the gift card code.
UPDATE tenders SET requires_reference = TRUE WHERE kind <> 'CASH';
UPDATE tenders SET requires_reference = FALSE WHERE kind = 'CASH';
UPDATE tenders SET counts_in_drawer = TRUE WHERE kind = 'CASH';

-- Payments and refunds keep the name of the tender they were made with, so old receipts and reports never change.
ALTER TABLE payments ADD COLUMN IF NOT EXISTS tender_name VARCHAR(40);
UPDATE payments p SET tender_name = COALESCE((SELECT t.name FROM tenders t WHERE t.code = p.method), p.method) WHERE p.tender_name IS NULL;
ALTER TABLE payments ALTER COLUMN tender_name SET NOT NULL;

ALTER TABLE refunds ADD COLUMN IF NOT EXISTS tender_name VARCHAR(40);
UPDATE refunds r SET tender_name = COALESCE((SELECT t.name FROM tenders t WHERE t.code = r.method), r.method) WHERE r.tender_name IS NULL;
ALTER TABLE refunds ALTER COLUMN tender_name SET NOT NULL;

-- A payment or refund can only name a tender that exists. A tender that has been used is never deleted, only turned off.
ALTER TABLE payments ADD CONSTRAINT IF NOT EXISTS fk_payments_tender FOREIGN KEY (method) REFERENCES tenders (code);
ALTER TABLE refunds ADD CONSTRAINT IF NOT EXISTS fk_refunds_tender FOREIGN KEY (method) REFERENCES tenders (code);
