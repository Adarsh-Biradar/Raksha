CREATE TABLE gateway_orders (
 transaction_id UUID PRIMARY KEY REFERENCES transactions(id),
 receipt VARCHAR(40) UNIQUE NOT NULL,
 provider_order_id TEXT UNIQUE,
 status TEXT NOT NULL CHECK(status IN ('CREATING','UNKNOWN','CREATED','AUTHORIZED','CAPTURED','FAILED')),
 created_by TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE gateway_attempts (
 payment_id TEXT PRIMARY KEY,
 transaction_id UUID NOT NULL REFERENCES gateway_orders(transaction_id),
 status TEXT NOT NULL CHECK(status IN ('created','authorized','captured','failed')),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE gateway_events (
 event_id TEXT PRIMARY KEY,
 payload_hash TEXT NOT NULL,
 event_type TEXT NOT NULL,
 order_id TEXT NOT NULL,
 payment_id TEXT NOT NULL,
 amount_minor BIGINT NOT NULL,
 currency TEXT NOT NULL,
 payment_status TEXT NOT NULL,
 state TEXT NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','PROCESSED','IGNORED','REJECTED')),
 attempts INT NOT NULL DEFAULT 0,
 available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX gateway_pending ON gateway_events(available_at) WHERE state='PENDING';
