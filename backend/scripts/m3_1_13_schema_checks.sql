-- CI-only assertions: migrations, tenant row-level controls, payment idempotency schema.
DO $check$
DECLARE tbl text;
BEGIN
 IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version='55' AND success) THEN
  RAISE EXCEPTION 'Final-pay V55 migration has not completed';
 END IF;
 FOREACH tbl IN ARRAY ARRAY[
  'payroll_personnel_identity_link',
  'payroll_final_pay_payment_authorization',
  'payroll_final_pay_bank_evidence',
  'payroll_final_pay_settlement'
 ] LOOP
  IF NOT EXISTS (
    SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE n.nspname='public' AND c.relname=tbl AND c.relrowsecurity AND c.relforcerowsecurity
  ) THEN RAISE EXCEPTION 'Tenant RLS and FORCE RLS missing on %',tbl; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE schemaname='public' AND tablename=tbl
  ) THEN RAISE EXCEPTION 'Tenant policy missing for %',tbl; END IF;
 END LOOP;
 IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='uq_identity_personnel') THEN
   RAISE EXCEPTION 'Personnel unique identity mapping is missing';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='uq_identity_payroll') THEN
   RAISE EXCEPTION 'Payroll unique identity mapping is missing';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='uq_fp_payment_settlement') THEN
   RAISE EXCEPTION 'Duplicate final-pay payment authorization is not blocked';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='uq_fp_evidence_bank_ref') THEN
   RAISE EXCEPTION 'Duplicate external bank references are not blocked';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_fp_payment_independent') THEN
   RAISE EXCEPTION 'Payment authorizer and preparer segregation constraint missing';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_fp_evidence_reviewers') THEN
   RAISE EXCEPTION 'Bank evidence reviewer segregation constraint missing';
 END IF;
END $check$;
