-- M3.1.19: protect human-entered bank evidence after review.
-- This does NOT make the reported bank reference an independently verified bank acknowledgement.
CREATE FUNCTION guard_fp_bank_evidence_mutation() RETURNS trigger
 LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'Bank evidence may not be deleted'
        USING ERRCODE='23514';
 END IF;
 IF OLD.status<>'SUBMITTED'
    OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
    OR NEW.payment_authorization_id IS DISTINCT FROM OLD.payment_authorization_id
    OR NEW.bank_code IS DISTINCT FROM OLD.bank_code
    OR NEW.bank_reference IS DISTINCT FROM OLD.bank_reference
    OR NEW.bank_value_date IS DISTINCT FROM OLD.bank_value_date
    OR NEW.evidence_document_reference IS DISTINCT FROM OLD.evidence_document_reference
    OR NEW.reported_amount IS DISTINCT FROM OLD.reported_amount
    OR NEW.recorded_by IS DISTINCT FROM OLD.recorded_by
    OR NEW.created_at IS DISTINCT FROM OLD.created_at
    OR NEW.status NOT IN ('EVIDENCE_REVIEWED','REJECTED')
    OR NEW.reviewed_by IS NULL
    OR NEW.reviewed_by=OLD.recorded_by
    OR NEW.reviewed_at IS NULL
 THEN
    RAISE EXCEPTION 'Bank evidence content and final decisions are immutable'
        USING ERRCODE='23514';
 END IF;
 IF EXISTS(
     SELECT 1 FROM payroll_final_pay_payment_authorization p
     WHERE p.tenant_id=NEW.tenant_id
       AND p.id=NEW.payment_authorization_id
       AND NEW.reviewed_by IN (p.prepared_by,p.authorized_by)
 ) THEN
    RAISE EXCEPTION 'Bank evidence reviewer must be independent of payment actors'
        USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fp_bank_evidence_guard BEFORE UPDATE OR DELETE
 ON payroll_final_pay_bank_evidence
 FOR EACH ROW EXECUTE FUNCTION guard_fp_bank_evidence_mutation();
