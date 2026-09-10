CREATE TABLE app_users (
 email TEXT PRIMARY KEY, password_hash TEXT NOT NULL, role TEXT NOT NULL CHECK(role IN ('ADMIN','ANALYST','VIEWER'))
);
CREATE TABLE transactions (
 id UUID PRIMARY KEY, event_id VARCHAR(100) UNIQUE NOT NULL, payload_hash TEXT NOT NULL,
 account_id VARCHAR(80) NOT NULL, amount_minor BIGINT NOT NULL CHECK(amount_minor > 0 AND amount_minor <= 100000000000),
 currency CHAR(3) NOT NULL CHECK(currency = 'INR'), merchant VARCHAR(100) NOT NULL,
 country CHAR(2) NOT NULL, device_id VARCHAR(80) NOT NULL, failed_attempts INT NOT NULL CHECK(failed_attempts BETWEEN 0 AND 100),
 occurred_at TIMESTAMPTZ NOT NULL, received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','SCORED','FAILED')),
 score INT CHECK(score BETWEEN 0 AND 100), classification TEXT,
 explanation TEXT, features TEXT, policy_version INT, late_event BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX tx_account_time ON transactions(account_id, occurred_at);
CREATE TABLE scoring_jobs (
 id UUID PRIMARY KEY, transaction_id UUID UNIQUE NOT NULL REFERENCES transactions(id),
 state TEXT NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','DONE','DEAD')),
 attempts INT NOT NULL DEFAULT 0, available_at TIMESTAMPTZ NOT NULL DEFAULT now(), last_error TEXT
);
CREATE INDEX job_ready ON scoring_jobs(state, available_at);
CREATE TABLE alerts (
 id UUID PRIMARY KEY, transaction_id UUID UNIQUE NOT NULL REFERENCES transactions(id),
 status TEXT NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','INVESTIGATING','RESOLVED')),
 assignee TEXT REFERENCES app_users(email), outcome TEXT, resolution TEXT,
 version INT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), resolved_at TIMESTAMPTZ
);
CREATE TABLE case_notes (
 id UUID PRIMARY KEY, alert_id UUID NOT NULL REFERENCES alerts(id), author TEXT NOT NULL,
 note TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE risk_policy (
 id INT PRIMARY KEY CHECK(id=1), version INT NOT NULL, amount_threshold_minor BIGINT NOT NULL CHECK(amount_threshold_minor > 0),
 velocity_limit INT NOT NULL CHECK(velocity_limit BETWEEN 2 AND 100),
 review_threshold INT NOT NULL CHECK(review_threshold BETWEEN 1 AND 98),
 high_threshold INT NOT NULL CHECK(high_threshold BETWEEN 2 AND 100 AND high_threshold > review_threshold)
);
INSERT INTO risk_policy VALUES(1,1,5000000,5,30,70);
CREATE TABLE audit_events (
 id BIGSERIAL PRIMARY KEY, actor TEXT NOT NULL, action TEXT NOT NULL, target TEXT NOT NULL,
 details TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
