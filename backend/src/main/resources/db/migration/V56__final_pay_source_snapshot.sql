-- M3.1.18: Append-only financial source evidence; not a payment or legal certification.
CREATE TABLE payroll_final_pay_source_snapshot (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 settlement_id uuid NOT NULL,
 payroll_employee_id uuid NOT NULL,
 source_sha256 char(64) NOT NULL CHECK(source_sha256 ~ '^[0-9a-f]{64}$'),
 evidence jsonb NOT NULL CHECK(jsonb_typeof(evidence)='object'),
 cutoff_gap_days integer NOT NULL CHECK(cutoff_gap_days>=0),
 compensation_gap_days integer NOT NULL CHECK(compensation_gap_days>=0),
 unfinalized_dtr_days integer NOT NULL CHECK(unfinalized_dtr_days>=0),
 completeness_status varchar(32) NOT NULL CHECK(completeness_status IN
    ('INCOMPLETE_SOURCE','REVIEW_REQUIRED')),
 captured_by uuid NOT NULL,
 captured_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT fk_fp_snapshot_settlement FOREIGN KEY (tenant_id,settlement_id)
   REFERENCES payroll_final_pay_settlement(tenant_id,id),
 CONSTRAINT fk_fp_snapshot_employee FOREIGN KEY (tenant_id,payroll_employee_id)
   REFERENCES employee(tenant_id,id),
 CONSTRAINT fk_fp_snapshot_actor FOREIGN KEY (tenant_id,captured_by)
   REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT uq_fp_snapshot_hash UNIQUE (tenant_id,settlement_id,source_sha256)
);
CREATE INDEX ix_fp_snapshot_settlement ON payroll_final_pay_source_snapshot
 (tenant_id,settlement_id,captured_at DESC);
ALTER TABLE payroll_final_pay_source_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_final_pay_source_snapshot FORCE ROW LEVEL SECURITY;
CREATE POLICY fp_snapshot_tenant ON payroll_final_pay_source_snapshot
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());

-- Even database-owning app sessions cannot silently overwrite or delete a capture.
CREATE FUNCTION reject_fp_source_snapshot_mutation() RETURNS trigger
 LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Final-pay evidence snapshots are append-only'
  USING ERRCODE='23514';
END $$;
CREATE TRIGGER fp_source_snapshot_immutable BEFORE UPDATE OR DELETE
 ON payroll_final_pay_source_snapshot
 FOR EACH ROW EXECUTE FUNCTION reject_fp_source_snapshot_mutation();
