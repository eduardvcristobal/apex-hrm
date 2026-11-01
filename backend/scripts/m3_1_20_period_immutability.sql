-- CI-only post-authenticated HTTP: attempt to mutate independently decided provenance.
BEGIN;
SELECT set_config('app.tenant_id','81000000-0000-4000-8000-000000000001',true);
DO $check$
DECLARE rec uuid;
BEGIN
 SELECT id INTO rec FROM payroll_final_pay_period_attestation
  WHERE tenant_id='81000000-0000-4000-8000-000000000001'
    AND status='REJECTED' LIMIT 1;
 IF rec IS NULL THEN RAISE EXCEPTION 'Expected rejected period attestation missing'; END IF;
 BEGIN
  UPDATE payroll_final_pay_period_attestation
   SET declared_document_sha256=repeat('a',64)
   WHERE id=rec;
  RAISE EXCEPTION 'Period document digest modification unexpectedly succeeded';
 EXCEPTION WHEN check_violation THEN NULL;
 END;
 BEGIN
  UPDATE payroll_final_pay_period_attestation
   SET status='INTERNALLY_REVIEWED'
   WHERE id=rec;
  RAISE EXCEPTION 'Period decision rewriting unexpectedly succeeded';
 EXCEPTION WHEN check_violation THEN NULL;
 END;
 BEGIN
  DELETE FROM payroll_final_pay_period_attestation WHERE id=rec;
  RAISE EXCEPTION 'Period attestation DELETE unexpectedly succeeded';
 EXCEPTION WHEN check_violation THEN NULL;
 END;
END $check$;
ROLLBACK;
