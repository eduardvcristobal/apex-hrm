-- M3.1.13.7: independently reviewed *external bank evidence*. No bank API confirmation.
CREATE UNIQUE INDEX IF NOT EXISTS uq_fp_payment_authorization_tenant_id
 ON payroll_final_pay_payment_authorization(tenant_id,id);
CREATE TABLE payroll_final_pay_bank_evidence (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 payment_authorization_id uuid NOT NULL,
 bank_code varchar(50) NOT NULL CHECK(length(trim(bank_code))>0),
 bank_reference varchar(160) NOT NULL CHECK(length(trim(bank_reference))>=6),
 bank_value_date date NOT NULL,
 evidence_document_reference varchar(1000) NOT NULL
    CHECK(length(trim(evidence_document_reference))>=20),
 reported_amount numeric(18,4) NOT NULL CHECK(reported_amount>0),
 status varchar(25) NOT NULL DEFAULT 'SUBMITTED'
    CHECK(status IN ('SUBMITTED','EVIDENCE_REVIEWED','REJECTED')),
 recorded_by uuid NOT NULL,
 reviewed_by uuid,
 reviewed_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT fk_fp_evidence_authorization FOREIGN KEY(tenant_id,payment_authorization_id)
     REFERENCES payroll_final_pay_payment_authorization(tenant_id,id),
 CONSTRAINT fk_fp_evidence_recorder FOREIGN KEY(tenant_id,recorded_by)
     REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_fp_evidence_reviewer FOREIGN KEY(tenant_id,reviewed_by)
     REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT uq_fp_evidence_authorization UNIQUE(tenant_id,payment_authorization_id),
 CONSTRAINT uq_fp_evidence_bank_ref UNIQUE(tenant_id,bank_code,bank_reference),
 CONSTRAINT ck_fp_evidence_reviewers CHECK(reviewed_by IS NULL OR reviewed_by<>recorded_by),
 CONSTRAINT ck_fp_evidence_decision CHECK(
   (status='SUBMITTED' AND reviewed_by IS NULL AND reviewed_at IS NULL)
   OR (status IN ('EVIDENCE_REVIEWED','REJECTED') AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL))
);
ALTER TABLE payroll_final_pay_bank_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_final_pay_bank_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY payroll_final_pay_bank_evidence_tenant ON payroll_final_pay_bank_evidence
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
CREATE INDEX ix_fp_bank_evidence_status ON payroll_final_pay_bank_evidence(tenant_id,status,created_at DESC);
