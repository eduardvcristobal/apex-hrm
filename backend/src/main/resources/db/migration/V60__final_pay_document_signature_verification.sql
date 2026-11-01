-- M3.1.21: preserve independently signed document-claim verification attempts.
-- A valid signature authenticates a configured issuer's DIGEST CLAIM only.
-- It does not validate uploaded bytes, bank settlement, recipient account or payouts.
CREATE UNIQUE INDEX IF NOT EXISTS uq_fp_period_attestation_tenant_id
  ON payroll_final_pay_period_attestation(tenant_id,id);
CREATE TABLE payroll_final_pay_document_verification (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 attestation_id uuid NOT NULL,
 issuer_key_id varchar(100) NOT NULL CHECK(length(trim(issuer_key_id))>=3),
 signed_claim_sha256 char(64) NOT NULL CHECK(signed_claim_sha256 ~ '^[0-9a-f]{64}$'),
 detached_signature_base64 varchar(512) NOT NULL CHECK(length(detached_signature_base64)>20),
 verification_status varchar(40) NOT NULL
   CHECK(verification_status='ISSUER_SIGNATURE_VALID_FOR_DIGEST'),
 verified_by uuid NOT NULL,
 verified_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT fk_fp_doc_verify_attestation FOREIGN KEY(tenant_id,attestation_id)
   REFERENCES payroll_final_pay_period_attestation(tenant_id,id),
 CONSTRAINT fk_fp_doc_verify_actor FOREIGN KEY(tenant_id,verified_by)
   REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT uq_fp_document_verification UNIQUE(tenant_id,attestation_id)
);
ALTER TABLE payroll_final_pay_document_verification ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_final_pay_document_verification FORCE ROW LEVEL SECURITY;
CREATE POLICY fp_document_verification_tenant ON payroll_final_pay_document_verification
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
CREATE FUNCTION reject_fp_document_verification_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Document signature verification is append-only' USING ERRCODE='23514';
END $$;
CREATE TRIGGER fp_document_verification_immutable BEFORE UPDATE OR DELETE
 ON payroll_final_pay_document_verification FOR EACH ROW
 EXECUTE FUNCTION reject_fp_document_verification_mutation();
