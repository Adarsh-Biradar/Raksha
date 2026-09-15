CREATE TABLE organizations (
 id UUID PRIMARY KEY, slug TEXT UNIQUE NOT NULL, name TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO organizations(id,slug,name) VALUES('00000000-0000-0000-0000-000000000001','default','Default Organization');

ALTER TABLE app_users ADD COLUMN org_id UUID REFERENCES organizations(id);
UPDATE app_users SET org_id='00000000-0000-0000-0000-000000000001';
ALTER TABLE app_users ALTER COLUMN org_id SET NOT NULL;

ALTER TABLE transactions ADD COLUMN org_id UUID REFERENCES organizations(id);
UPDATE transactions SET org_id='00000000-0000-0000-0000-000000000001';
ALTER TABLE transactions ALTER COLUMN org_id SET NOT NULL;
CREATE INDEX tx_org ON transactions(org_id);

-- risk_policy/notification_settings/rule_lab were single global rows (id=1). Move to one row per
-- organization: drop the id=1 constraint, key lookups by org_id instead. `id` stays as-is (still 1
-- for the existing row) since creating additional organizations is out of scope for this pass.
ALTER TABLE risk_policy DROP CONSTRAINT risk_policy_id_check;
ALTER TABLE risk_policy ADD COLUMN org_id UUID REFERENCES organizations(id);
UPDATE risk_policy SET org_id='00000000-0000-0000-0000-000000000001';
ALTER TABLE risk_policy ALTER COLUMN org_id SET NOT NULL;
ALTER TABLE risk_policy ADD CONSTRAINT risk_policy_org_unique UNIQUE(org_id);

ALTER TABLE notification_settings DROP CONSTRAINT notification_settings_id_check;
ALTER TABLE notification_settings ADD COLUMN org_id UUID REFERENCES organizations(id);
UPDATE notification_settings SET org_id='00000000-0000-0000-0000-000000000001';
ALTER TABLE notification_settings ALTER COLUMN org_id SET NOT NULL;
ALTER TABLE notification_settings ADD CONSTRAINT notification_settings_org_unique UNIQUE(org_id);

ALTER TABLE rule_lab DROP CONSTRAINT rule_lab_id_check;
ALTER TABLE rule_lab ADD COLUMN org_id UUID REFERENCES organizations(id);
UPDATE rule_lab SET org_id='00000000-0000-0000-0000-000000000001';
ALTER TABLE rule_lab ALTER COLUMN org_id SET NOT NULL;
ALTER TABLE rule_lab ADD CONSTRAINT rule_lab_org_unique UNIQUE(org_id);
