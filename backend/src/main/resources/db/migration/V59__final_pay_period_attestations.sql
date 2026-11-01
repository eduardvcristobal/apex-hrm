-- M3.1.20: independently reviewed period-closure attestations and document provenance.
-- A claimed external document fingerprint is NOT a verified bank acknowledgement.
CREATE UNIQUE INDEX IF NOT EXISTS uq_fp_source_review_tenant_id
 ON payroll_final_pay_source_review(tenant_id,id);

CREATE TABLE payroll_final_pay_period_attestation (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 settlement_id uuid NOT NULL,
 snapshot_id uuid NOT NULL,
 source_review_id uuid NOT NULL,
 source_sha256 char(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
 issuer_name varchar(200) NOT NULL CHECK (length(trim(issuer_name))>=3),
 document_reference varchar(1000) NOT NULL CHECK (length(trim(document_reference))>=20),
 declared_document_sha256 char(64) NOT NULL CHECK (declared_document_sha256 ~ '^[0-9a-f]{64}$'),
 provenance_status varchar(40) NOT NULL DEFAULT 'USER_DECLARED_UNVERIFIED'
   CHECK (provenance_status='USER_DECLARED_UNVERIFIED'),
 status varchar(28) NOT NULL DEFAULT 'PENDING'
   CHECK (status IN ('PENDING','INTERNALLY_REVIEWED','REJECTED')),
 requested_by uuid NOT NULL,
 reviewed_by uuid,
 reviewed_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT fk_fp_attestation_settlement FOREIGN KEY (tenant_id,settlement_id)
   REFERENCES payroll_final_pay_settlement(tenant_id,id),
 CONSTRAINT fk_fp_attestation_snapshot FOREIGN KEY (tenant_id,snapshot_id)
   REFERENCES payroll_final_pay_source_snapshot(tenant_id,id),
 CONSTRAINT fk_fp_attestation_source_review FOREIGN KEY (tenant_id,source_review_id)
   REFERENCES payroll_final_pay_source_review(tenant_id,id),
 CONSTRAINT fk_fp_attestation_requester FOREIGN KEY (tenant_id,requested_by)
   REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_fp_attestation_reviewer FOREIGN KEY (tenant_id,reviewed_by)
   REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT uq_fp_attestation_snapshot UNIQUE (tenant_id,snapshot_id),
 CONSTRAINT ck_fp_attestation_independent CHECK (reviewed_by IS NULL OR reviewed_by<>requested_by),
 CONSTRAINT ck_fp_attestation_decision CHECK(
    (status='PENDING' AND reviewed_by IS NULL AND reviewed_at IS NULL)
    OR (status IN ('INTERNALLY_REVIEWED','REJECTED')
        AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL))
);
CREATE INDEX ix_fp_attestation_settlement ON payroll_final_pay_period_attestation
 (tenant_id,settlement_id,created_at DESC);
ALTER TABLE payroll_final_pay_period_attestation ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_final_pay_period_attestation FORCE ROW LEVEL SECURITY;
CREATE POLICY fp_attestation_tenant ON payroll_final_pay_period_attestation
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());

-- Preserve submitted provenance and prevent any final decision rewrite, even by app SQL.
CREATE FUNCTION guard_fp_attestation_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.status<>'PENDING'
   OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
   OR NEW.settlement_id IS DISTINCT FROM OLD.settlement_id
   OR NEW.snapshot_id IS DISTINCT FROM OLD.snapshot_id
   OR NEW.source_review_id IS DISTINCT FROM OLD.source_review_id
   OR NEW.source_sha256 IS DISTINCT FROM OLD.source_sha256
   OR NEW.issuer_name IS DISTINCT FROM OLD.issuer_name
   OR NEW.document_reference IS DISTINCT FROM OLD.document_reference
   OR NEW.declared_document_sha256 IS DISTINCT FROM OLD.declared_document_sha256
   OR NEW.provenance_status IS DISTINCT FROM OLD.provenance_status
   OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
   OR NEW.created_at IS DISTINCT FROM OLD.created_at
   OR NEW.status NOT IN ('INTERNALLY_REVIEWED','REJECTED')
   OR NEW.reviewed_by IS NULL OR NEW.reviewed_by=OLD.requested_by
   OR NEW.reviewed_at IS NULL
 THEN
  RAISE EXCEPTION 'Final-pay period attestation provenance and decisions are immutable'
    USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fp_attestation_immutable BEFORE UPDATE OR DELETE
 ON payroll_final_pay_period_attestation FOR EACH ROW
 EXECUTE FUNCTION guard_fp_attestation_mutation();
