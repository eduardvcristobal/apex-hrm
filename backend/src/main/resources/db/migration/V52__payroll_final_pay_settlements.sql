-- M3.1.12: HR separation handoff -> payroll-owned reviewed final-pay settlement.
-- Preserve tenant-scoped FK integrity and force RLS (same policy as the personnel handoff).
-- Handoff has a primary key on id but no (tenant_id,id) unique key for a composite FK.
CREATE UNIQUE INDEX uq_personnel_fp_handoff_tenant_id ON personnel_final_pay_handoff(tenant_id,id);
CREATE TABLE payroll_final_pay_settlement (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 handoff_id uuid NOT NULL,
 employee_id uuid NOT NULL,
 case_id uuid NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'PENDING'
   CHECK(status IN ('PENDING','APPROVED','REJECTED')),
 unpaid_basic numeric(18,4) NOT NULL CHECK(unpaid_basic>=0),
 accrued_thirteenth_month numeric(18,4) NOT NULL CHECK(accrued_thirteenth_month>=0),
 unused_leave_conversion numeric(18,4) NOT NULL CHECK(unused_leave_conversion>=0),
 separation_pay numeric(18,4) NOT NULL CHECK(separation_pay>=0),
 other_earnings numeric(18,4) NOT NULL CHECK(other_earnings>=0),
 outstanding_loans numeric(18,4) NOT NULL CHECK(outstanding_loans>=0),
 accountable_property numeric(18,4) NOT NULL CHECK(accountable_property>=0),
 withholding_tax numeric(18,4) NOT NULL CHECK(withholding_tax>=0),
 other_authorized_deductions numeric(18,4) NOT NULL CHECK(other_authorized_deductions>=0),
 gross numeric(18,4) NOT NULL CHECK(gross>=0),
 deductions numeric(18,4) NOT NULL CHECK(deductions>=0),
 net numeric(18,4) NOT NULL,
 accounting_reference varchar(1000) NOT NULL,
 justification varchar(2000) NOT NULL,
 requested_by uuid NOT NULL,
 approved_by uuid,
 approved_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT fk_fp_handoff FOREIGN KEY(tenant_id,handoff_id)
   REFERENCES personnel_final_pay_handoff(tenant_id,id),
 CONSTRAINT fk_fp_employee FOREIGN KEY(tenant_id,employee_id)
   REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_fp_case FOREIGN KEY(tenant_id,case_id)
   REFERENCES personnel_hr_case(tenant_id,id),
 CONSTRAINT fk_fp_requester FOREIGN KEY(tenant_id,requested_by)
   REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_fp_approver FOREIGN KEY(tenant_id,approved_by)
   REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT uq_fp_handoff UNIQUE(tenant_id,handoff_id),
 CONSTRAINT ck_fp_approver_distinct CHECK(approved_by IS NULL OR approved_by<>requested_by),
 CONSTRAINT ck_fp_decision CHECK (
 (status='PENDING' AND approved_by IS NULL AND approved_at IS NULL)
 OR (status IN ('APPROVED','REJECTED') AND approved_by IS NOT NULL AND approved_at IS NOT NULL))
);
ALTER TABLE payroll_final_pay_settlement ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_final_pay_settlement FORCE ROW LEVEL SECURITY;
CREATE POLICY payroll_final_pay_tenant ON payroll_final_pay_settlement
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
CREATE INDEX ix_final_pay_settlement_employee ON payroll_final_pay_settlement(tenant_id,employee_id,created_at DESC);
