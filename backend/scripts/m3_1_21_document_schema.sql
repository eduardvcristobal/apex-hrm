-- M3.1.21: append-only issuer digest proof and forced RLS.
BEGIN;
DO $assert$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_class WHERE relname='payroll_final_pay_document_verification'
   AND relrowsecurity AND relforcerowsecurity) THEN
   RAISE EXCEPTION 'Document verification must FORCE tenant RLS';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgname='fp_document_verification_immutable'
   AND NOT tgisinternal) THEN
   RAISE EXCEPTION 'Document verification immutability trigger missing';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conname='uq_fp_document_verification') THEN
   RAISE EXCEPTION 'Verified issuer claim must be unique per attestation';
 END IF;
END $assert$;
ROLLBACK;
