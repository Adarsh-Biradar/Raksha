-- Fixes cross-tenant reads left over from the V10 organizations migration: several tables that
-- carry per-transaction/per-org data had no org_id column, so their admin-facing list endpoints
-- returned every organization's rows instead of the caller's own. See docs/SECURITY_AUDIT.md #2-4.

ALTER TABLE email_deliveries ADD COLUMN org_id UUID REFERENCES organizations(id);
UPDATE email_deliveries SET org_id='00000000-0000-0000-0000-000000000001';
ALTER TABLE email_deliveries ALTER COLUMN org_id SET NOT NULL;

ALTER TABLE sms_deliveries ADD COLUMN org_id UUID REFERENCES organizations(id);
UPDATE sms_deliveries SET org_id='00000000-0000-0000-0000-000000000001';
ALTER TABLE sms_deliveries ALTER COLUMN org_id SET NOT NULL;

ALTER TABLE scoring_jobs ADD COLUMN org_id UUID REFERENCES organizations(id);
UPDATE scoring_jobs SET org_id='00000000-0000-0000-0000-000000000001';
ALTER TABLE scoring_jobs ALTER COLUMN org_id SET NOT NULL;

ALTER TABLE ai_eval_runs ADD COLUMN org_id UUID REFERENCES organizations(id);
UPDATE ai_eval_runs SET org_id='00000000-0000-0000-0000-000000000001';
ALTER TABLE ai_eval_runs ALTER COLUMN org_id SET NOT NULL;

-- audit_events spans worker-originated rows that cannot always be attributed to one organization
-- (e.g. a webhook payment event rejected before it matches any stored order). Nullable by design:
-- an unattributed row is simply invisible to every organization's audit view, never shown to the
-- wrong one.
ALTER TABLE audit_events ADD COLUMN org_id UUID REFERENCES organizations(id);
UPDATE audit_events SET org_id='00000000-0000-0000-0000-000000000001';

CREATE INDEX email_deliveries_org ON email_deliveries(org_id);
CREATE INDEX sms_deliveries_org ON sms_deliveries(org_id);
CREATE INDEX scoring_jobs_org ON scoring_jobs(org_id);
CREATE INDEX ai_eval_runs_org ON ai_eval_runs(org_id);
CREATE INDEX audit_events_org ON audit_events(org_id);
