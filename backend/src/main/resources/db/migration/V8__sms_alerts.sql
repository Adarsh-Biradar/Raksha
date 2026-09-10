ALTER TABLE notification_settings ADD COLUMN merchant_phone VARCHAR(20);
CREATE TABLE sms_deliveries (
 id UUID PRIMARY KEY, transaction_id UUID NOT NULL REFERENCES transactions(id), recipient VARCHAR(20) NOT NULL,
 outcome TEXT NOT NULL CHECK(outcome IN ('SUCCESS','FAILED')), merchant VARCHAR(100) NOT NULL,
 amount_minor BIGINT NOT NULL, currency CHAR(3) NOT NULL,
 state TEXT NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','SENT','FAILED')),
 attempts INT NOT NULL DEFAULT 0, available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), sent_at TIMESTAMPTZ, last_error TEXT,
 UNIQUE(transaction_id,outcome)
);
CREATE INDEX sms_ready ON sms_deliveries(state,available_at);
