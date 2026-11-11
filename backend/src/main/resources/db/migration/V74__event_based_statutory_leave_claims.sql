ALTER TABLE leave_benefit_entitlement ADD COLUMN grant_basis varchar(16) NOT NULL DEFAULT 'ANNUAL';
ALTER TABLE leave_benefit_entitlement ADD CONSTRAINT ck_leave_benefit_grant_basis CHECK (grant_basis IN ('ANNUAL','EVENT'));

-- Maternity and paternity days are triggered by qualifying events, not annual employee grants.
UPDATE leave_benefit_entitlement e SET grant_basis='EVENT',hire_proration_enabled=false,convertible=false,carry_over_max=NULL
FROM leave_type t WHERE t.tenant_id=e.tenant_id AND t.id=e.leave_type_id AND t.code IN ('ML','PL');
UPDATE leave_type SET annual_entitlement=NULL,hire_proration_enabled=false WHERE code IN ('ML','PL');

-- Remove only the unspent balance from past recurring grants; consumed leave remains in the immutable ledger.
WITH affected AS (
  SELECT DISTINCT l.tenant_id,l.employee_id,l.leave_type_id FROM leave_ledger l
  JOIN leave_type t ON t.tenant_id=l.tenant_id AND t.id=l.leave_type_id
  WHERE t.code IN ('ML','PL') AND l.reference_type='BENEFIT_PROFILE_ANNUAL'
), remaining AS (
  SELECT a.tenant_id,a.employee_id,a.leave_type_id,sum(l.days) AS balance
  FROM affected a JOIN leave_ledger l USING (tenant_id,employee_id,leave_type_id)
  GROUP BY a.tenant_id,a.employee_id,a.leave_type_id HAVING sum(l.days)>0
)
INSERT INTO leave_ledger(tenant_id,employee_id,leave_type_id,entry_date,entry_type,days,reference_type,remarks)
SELECT tenant_id,employee_id,leave_type_id,current_date,'ADJUSTMENT',-balance,
  'EVENT_BASIS_CORRECTION','Removed unspent balance from prior recurring event-leave grants' FROM remaining;

CREATE TABLE leave_benefit_claim (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  employee_id uuid NOT NULL,
  leave_type_id uuid NOT NULL,
  profile_id uuid NOT NULL,
  event_date date NOT NULL,
  event_reference varchar(120) NOT NULL,
  requested_days numeric(18,4) NOT NULL CHECK (requested_days > 0),
  evidence_reference varchar(255) NOT NULL,
  reason varchar(1000),
  status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED')),
  eligibility_verified boolean NOT NULL DEFAULT false,
  requested_by uuid NOT NULL,
  decided_by uuid,
  decided_at timestamptz,
  employer_salary_differential numeric(18,4) NOT NULL DEFAULT 0 CHECK (employer_salary_differential >= 0),
  agency_reimbursement_due numeric(18,4) NOT NULL DEFAULT 0 CHECK (agency_reimbursement_due >= 0),
  reimbursement_status varchar(16) NOT NULL DEFAULT 'NOT_TRACKED'
    CHECK (reimbursement_status IN ('NOT_TRACKED','PENDING','SUBMITTED','REIMBURSED','REJECTED')),
  reimbursement_reference varchar(255),
  CONSTRAINT uq_leave_benefit_claim_event UNIQUE(tenant_id,employee_id,leave_type_id,event_date,event_reference),
  FOREIGN KEY (tenant_id,employee_id) REFERENCES employee(tenant_id,id),
  FOREIGN KEY (tenant_id,leave_type_id) REFERENCES leave_type(tenant_id,id),
  FOREIGN KEY (tenant_id,profile_id) REFERENCES leave_benefit_profile(tenant_id,id),
  FOREIGN KEY (tenant_id,requested_by) REFERENCES auth_identity(tenant_id,id),
  FOREIGN KEY (tenant_id,decided_by) REFERENCES auth_identity(tenant_id,id),
  CHECK ((status='PENDING' AND decided_by IS NULL AND decided_at IS NULL)
      OR (status<>'PENDING' AND decided_by IS NOT NULL AND decided_at IS NOT NULL)),
  CHECK (status<>'APPROVED' OR eligibility_verified)
);
ALTER TABLE leave_benefit_claim ENABLE ROW LEVEL SECURITY;
ALTER TABLE leave_benefit_claim FORCE ROW LEVEL SECURITY;
CREATE POLICY leave_benefit_claim_tenant ON leave_benefit_claim
  USING (tenant_id=app_tenant_id()) WITH CHECK (tenant_id=app_tenant_id());
CREATE INDEX ix_leave_benefit_claim_employee ON leave_benefit_claim(tenant_id,employee_id,event_date DESC);
CREATE INDEX ix_leave_benefit_claim_status ON leave_benefit_claim(tenant_id,status,created_at DESC);
