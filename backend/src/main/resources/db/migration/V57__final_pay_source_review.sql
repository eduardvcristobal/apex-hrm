-- M3.1.19: source completeness review, NOT legal final-pay or bank payout authorization.
-- One review per immutable snapshot, independently requested and decided.
CREATE UNIQUE INDEX IF NOT EXISTS uq_fp_snapshot_tenant_id
 ON payroll_final_pay_source_snapshot(tenant_id,id);
CREATE TABLE payroll_final_pay_source_review (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 settlement_id uuid NOT NULL,
 snapshot_id uuid NOT NULL,
 requested_sha256 char(64) NOT NULL CHECK(requested_sha256 ~ '^[0-9a-f]{64}$'),
 evidence_reference varchar(1000) NOT NULL
   CHECK(length(trim(evidence_reference))>=20),
 status varchar(24) NOT NULL DEFAULT 'PENDING'
   CHECK(status IN ('PENDING','SOURCE_REVIEWED','REJECTED')),
 requested_by uuid NOT NULL,
 decided_by uuid,
 decided_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT fk_fp_source_review_snapshot FOREIGN KEY(tenant_id,snapshot_id)
   REFERENCES payroll_final_pay_source_snapshot(tenant_id,id),
 CONSTRAINT fk_fp_source_review_settlement FOREIGN KEY(tenant_id,settlement_id)
   REFERENCES payroll_final_pay_settlement(tenant_id,id),
 CONSTRAINT fk_fp_source_review_requester FOREIGN KEY(tenant_id,requested_by)
   REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_fp_source_review_decider FOREIGN KEY(tenant_id,decided_by)
   REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT uq_fp_source_review_snapshot UNIQUE(tenant_id,snapshot_id),
 CONSTRAINT ck_fp_source_review_independent CHECK(decided_by IS NULL OR decided_by<>requested_by),
 CONSTRAINT ck_fp_source_review_decision CHECK(
   (status='PENDING' AND decided_by IS NULL AND decided_at IS NULL)
   OR (status IN ('SOURCE_REVIEWED','REJECTED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL))
);
ALTER TABLE payroll_final_pay_source_review ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_final_pay_source_review FORCE ROW LEVEL SECURITY;
CREATE POLICY fp_source_review_tenant ON payroll_final_pay_source_review
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
CREATE INDEX ix_fp_source_review_settlement
 ON payroll_final_pay_source_review(tenant_id,settlement_id,created_at DESC);
-- Once a source review reaches a decision, its status and evidence cannot be rewritten.
CREATE FUNCTION guard_fp_source_review_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.status<>'PENDING'
    OR NEW.tenant_id<>OLD.tenant_id
    OR NEW.snapshot_id<>OLD.snapshot_id
    OR NEW.settlement_id<>OLD.settlement_id
    OR NEW.requested_sha256<>OLD.requested_sha256
    OR NEW.evidence_reference<>OLD.evidence_reference
    OR NEW.requested_by<>OLD.requested_by
    OR NEW.created_at<>OLD.created_at
    OR NEW.status NOT IN ('SOURCE_REVIEWED','REJECTED')
    OR NEW.decided_by IS NULL OR NEW.decided_by=OLD.requested_by
    OR NEW.decided_at IS NULL
 THEN
    RAISE EXCEPTION 'Source review evidence and decisions are immutable'
      USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fp_source_review_guard BEFORE UPDATE OR DELETE
 ON payroll_final_pay_source_review
 FOR EACH ROW EXECUTE FUNCTION guard_fp_source_review_mutation();
