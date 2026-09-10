CREATE TABLE notification_settings (
 id INT PRIMARY KEY CHECK(id=1), version INT NOT NULL DEFAULT 1,
 enabled BOOLEAN NOT NULL DEFAULT false,
 minimum_classification TEXT NOT NULL DEFAULT 'HIGH_RISK' CHECK(minimum_classification IN ('HIGH_RISK','SUSPICIOUS')),
 recipients TEXT NOT NULL DEFAULT ''
);
INSERT INTO notification_settings(id) VALUES(1);
CREATE TABLE email_deliveries (
 id UUID PRIMARY KEY, alert_id UUID REFERENCES alerts(id), recipient VARCHAR(254) NOT NULL,
 classification TEXT NOT NULL, score INT, transaction_id UUID REFERENCES transactions(id),
 state TEXT NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','SENT','FAILED','CANCELLED')),
 attempts INT NOT NULL DEFAULT 0, available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), sent_at TIMESTAMPTZ, last_error TEXT,
 UNIQUE(alert_id,recipient)
);
CREATE INDEX email_ready ON email_deliveries(state,available_at);
