-- M3.1.13: Explicitly reviewed cross-model employee identities; never join employees on name alone.
CREATE UNIQUE INDEX IF NOT EXISTS uq_legacy_employee_tenant_id ON employee(tenant_id,id);
CREATE TABLE payroll_personnel_identity_link (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 personnel_employee_id uuid NOT NULL,
 payroll_employee_id uuid NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','APPROVED','REJECTED')),
 evidence_reference varchar(1000) NOT NULL CHECK(length(trim(evidence_reference))>=20),
 requested_by uuid NOT NULL,
 decided_by uuid,
 decided_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT fk_identity_personnel FOREIGN KEY(tenant_id,personnel_employee_id) REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_identity_payroll FOREIGN KEY(tenant_id,payroll_employee_id) REFERENCES employee(tenant_id,id),
 CONSTRAINT fk_identity_requester FOREIGN KEY(tenant_id,requested_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_identity_decider FOREIGN KEY(tenant_id,decided_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT uq_identity_personnel UNIQUE(tenant_id,personnel_employee_id),
 CONSTRAINT uq_identity_payroll UNIQUE(tenant_id,payroll_employee_id),
 CONSTRAINT uq_identity_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT ck_identity_two_person CHECK(decided_by IS NULL OR decided_by<>requested_by),
 CONSTRAINT ck_identity_decision CHECK(
  (status='PENDING' AND decided_by IS NULL AND decided_at IS NULL) OR
  (status IN ('APPROVED','REJECTED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL))
);
ALTER TABLE payroll_personnel_identity_link ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_personnel_identity_link FORCE ROW LEVEL SECURITY;
CREATE POLICY payroll_identity_tenant ON payroll_personnel_identity_link
 USING (tenant_id=app_tenant_id()) WITH CHECK (tenant_id=app_tenant_id());
CREATE INDEX ix_payroll_identity_status ON payroll_personnel_identity_link(tenant_id,status);
