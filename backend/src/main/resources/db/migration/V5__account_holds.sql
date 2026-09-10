ALTER TABLE transactions DROP CONSTRAINT transactions_status_check;
ALTER TABLE transactions ADD CONSTRAINT transactions_status_check CHECK(status IN ('PENDING','SCORED','FAILED','BLOCKED'));
CREATE TABLE account_holds (alert_id UUID PRIMARY KEY REFERENCES alerts(id), account_id VARCHAR(80) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), released_at TIMESTAMPTZ, released_by TEXT);
CREATE INDEX active_account_holds ON account_holds(account_id) WHERE released_at IS NULL;
