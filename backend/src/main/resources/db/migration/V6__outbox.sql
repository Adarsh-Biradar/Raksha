CREATE TABLE outbox_events (
 id UUID PRIMARY KEY, transaction_id UUID UNIQUE NOT NULL REFERENCES transactions(id),
 event_id VARCHAR(100) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), published_at TIMESTAMPTZ
);
CREATE INDEX outbox_ready ON outbox_events(published_at, created_at);
