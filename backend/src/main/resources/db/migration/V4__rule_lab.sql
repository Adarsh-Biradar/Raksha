CREATE TABLE rule_lab (
 id INT PRIMARY KEY CHECK(id=1), version INT NOT NULL DEFAULT 1,
 mode TEXT NOT NULL DEFAULT 'OFF' CHECK(mode IN ('OFF','SHADOW','ACTIVE')),
 repeat_count INT NOT NULL DEFAULT 5 CHECK(repeat_count BETWEEN 2 AND 20),
 window_minutes INT NOT NULL DEFAULT 5 CHECK(window_minutes BETWEEN 1 AND 60),
 points INT NOT NULL DEFAULT 100 CHECK(points BETWEEN 1 AND 100)
);
INSERT INTO rule_lab(id) VALUES(1);
ALTER TABLE transactions ADD COLUMN base_risk_points INT;
CREATE INDEX tx_repeated_amount ON transactions(account_id,currency,amount_minor,occurred_at);
CREATE TABLE rule_lab_observations(
 transaction_id UUID PRIMARY KEY REFERENCES transactions(id),
 rule_version INT NOT NULL, matching_count BIGINT NOT NULL,
 actual_score INT NOT NULL, proposed_score INT NOT NULL,
 configuration TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
