-- PostgreSQL 16 CI-only source-review security acceptance (no production updates).
BEGIN;
DO $verify$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname='payroll_final_pay_source_review'
     AND relrowsecurity AND relforcerowsecurity) THEN
   RAISE EXCEPTION 'Source-review table must force RLS';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname='fp_source_review_guard'
     AND NOT tgisinternal) THEN
   RAISE EXCEPTION 'Source-review mutation guard is absent';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_fp_source_review_independent') THEN
   RAISE EXCEPTION 'Independent decision database constraint is missing';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='uq_fp_source_review_snapshot') THEN
   RAISE EXCEPTION 'Snapshot review idempotency constraint missing';
 END IF;
END $verify$;
ROLLBACK;
