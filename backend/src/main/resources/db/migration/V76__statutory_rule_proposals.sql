CREATE TABLE statutory_rule_proposal (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  submitted_by uuid NOT NULL,
  submitted_at timestamptz NOT NULL DEFAULT now(),
  rule_type varchar(30) NOT NULL CHECK (rule_type IN ('CONTRIBUTION','TAX_BRACKET','PREMIUM','WAGE_ORDER')),
  effective_from date NOT NULL,
  payload jsonb NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED')),
  reviewed_by uuid,
  reviewed_at timestamptz,
  review_note varchar(1000),
  applied_rule_id uuid,
  CONSTRAINT ck_statutory_proposal_review CHECK (
    (status='PENDING' AND reviewed_by IS NULL AND reviewed_at IS NULL)
    OR (status<>'PENDING' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
  )
);
CREATE INDEX idx_statutory_proposal_tenant ON statutory_rule_proposal(tenant_id,submitted_at DESC);
CREATE INDEX idx_statutory_proposal_queue ON statutory_rule_proposal(status,submitted_at);
ALTER TABLE statutory_rule_proposal ENABLE ROW LEVEL SECURITY;
ALTER TABLE statutory_rule_proposal FORCE ROW LEVEL SECURITY;
CREATE POLICY statutory_proposal_tenant ON statutory_rule_proposal
  USING (tenant_id=app_tenant_id() OR current_setting('app.platform_admin',true)='true')
  WITH CHECK (tenant_id=app_tenant_id() OR current_setting('app.platform_admin',true)='true');
