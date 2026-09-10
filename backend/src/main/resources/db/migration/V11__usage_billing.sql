ALTER TABLE organizations ADD COLUMN plan TEXT NOT NULL DEFAULT 'FREE' CHECK(plan IN ('FREE','PRO','ENTERPRISE'));
ALTER TABLE organizations ADD COLUMN monthly_transaction_cap INT;
UPDATE organizations SET monthly_transaction_cap=500 WHERE plan='FREE';

-- One row per organization per calendar month ("2026-09"). Incremented transactionally in
-- FraudService.ingest() so a cap breach rolls back the whole ingest, not just the counter.
CREATE TABLE usage_counters (
 org_id UUID NOT NULL REFERENCES organizations(id),
 period TEXT NOT NULL,
 transactions_count BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(org_id,period)
);
