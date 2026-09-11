CREATE TABLE ai_explanations (
 id UUID PRIMARY KEY, transaction_id UUID UNIQUE NOT NULL REFERENCES transactions(id),
 narrative TEXT, model TEXT, prompt_tokens INT, completion_tokens INT, latency_ms INT,
 state TEXT NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','SENT','FAILED')),
 attempts INT NOT NULL DEFAULT 0, available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), sent_at TIMESTAMPTZ, last_error TEXT
);
CREATE INDEX ai_explanations_ready ON ai_explanations(state,available_at);
CREATE TABLE ai_eval_runs (
 id UUID PRIMARY KEY, run_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 total INT NOT NULL, passed INT NOT NULL, failed INT NOT NULL, details TEXT NOT NULL
);
