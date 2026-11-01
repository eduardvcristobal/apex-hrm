-- M3.1.13.3: staged final-pay payment authorization; no banking-network integration.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payroll_fp_settlement_tenant_id
 ON payroll_final_pay_settlement(tenant_id,id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_legacy_bank_account_tenant_id
 ON employee_bank_account(tenant_id,id);
CREATE TABLE payroll_final_pay_payment_authorization (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 settlement_id uuid NOT NULL,
 identity_link_id uuid NOT NULL,
 bank_account_id uuid NOT NULL,
 amount numeric(18,4) NOT NULL CHECK(amount>0),
 status varchar(22) NOT NULL DEFAULT 'PREPARED'
    CHECK(status IN ('PREPARED','AUTHORIZED','CANCELLED')),
 source_reconciliation_reference varchar(1000) NOT NULL
    CHECK(length(trim(source_reconciliation_reference))>=20),
 prepared_by uuid NOT NULL,
 authorized_by uuid,
 authorized_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT fk_fp_payment_settlement FOREIGN KEY(tenant_id,settlement_id)
     REFERENCES payroll_final_pay_settlement(tenant_id,id),
 CONSTRAINT fk_fp_payment_identity FOREIGN KEY(tenant_id,identity_link_id)
     REFERENCES payroll_personnel_identity_link(tenant_id,id),
 CONSTRAINT fk_fp_payment_bank FOREIGN KEY(tenant_id,bank_account_id)
     REFERENCES employee_bank_account(tenant_id,id),
 CONSTRAINT fk_fp_payment_preparer FOREIGN KEY(tenant_id,prepared_by)
     REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_fp_payment_authorizer FOREIGN KEY(tenant_id,authorized_by)
     REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT uq_fp_payment_settlement UNIQUE(tenant_id,settlement_id),
 CONSTRAINT ck_fp_payment_independent CHECK(authorized_by IS NULL OR authorized_by<>prepared_by),
 CONSTRAINT ck_fp_payment_state CHECK(
   (status='PREPARED' AND authorized_by IS NULL AND authorized_at IS NULL)
    OR (status IN ('AUTHORIZED','CANCELLED') AND authorized_by IS NOT NULL AND authorized_at IS NOT NULL))
);
 ALTER TABLE payroll_final_pay_payment_authorization ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_final_pay_payment_authorization FORCE ROW LEVEL SECURITY;
CREATE POLICY payroll_fp_payment_tenant ON payroll_final_pay_payment_authorization
 USING (tenant_id=app_tenant_id()) WITH CHECK (tenant_id=app_tenant_id());
CREATE INDEX ix_fp_payment_status ON payroll_final_pay_payment_authorization(tenant_id,status,created_at DESC);
