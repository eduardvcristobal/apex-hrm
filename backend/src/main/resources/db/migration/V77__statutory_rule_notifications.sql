CREATE TABLE statutory_rule_notification (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  recipient_id uuid NOT NULL REFERENCES auth_identity(id) ON DELETE CASCADE,
  proposal_id uuid NOT NULL REFERENCES statutory_rule_proposal(id),
  status varchar(20) NOT NULL CHECK (status IN ('APPROVED','REJECTED')),
  message varchar(1200) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  read_at timestamptz,
  UNIQUE(proposal_id)
);
CREATE INDEX idx_statutory_rule_notification_inbox ON statutory_rule_notification(tenant_id,recipient_id,read_at,created_at DESC);
ALTER TABLE statutory_rule_notification ENABLE ROW LEVEL SECURITY;
ALTER TABLE statutory_rule_notification FORCE ROW LEVEL SECURITY;
CREATE POLICY statutory_rule_notification_tenant ON statutory_rule_notification
  USING (tenant_id=app_tenant_id() OR current_setting('app.platform_admin',true)='true')
  WITH CHECK (tenant_id=app_tenant_id() OR current_setting('app.platform_admin',true)='true');
