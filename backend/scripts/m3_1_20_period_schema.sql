-- M3.1.20 PostgreSQL 16 schema-only assertions.
BEGIN;
DO $check$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_class
    WHERE relname='payroll_final_pay_period_attestation'
      AND relrowsecurity AND relforcerowsecurity)
 THEN RAISE EXCEPTION 'Period attestation forced tenant RLS missing'; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_trigger
    WHERE tgname='fp_attestation_immutable' AND NOT tgisinternal)
 THEN RAISE EXCEPTION 'Period attestation immutability guard missing'; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_constraint
    WHERE conname='ck_fp_attestation_independent')
 THEN RAISE EXCEPTION 'Period attestation independent reviewer constraint missing'; END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_constraint
    WHERE conname='uq_fp_attestation_snapshot')
 THEN RAISE EXCEPTION 'Period attestation duplicate snapshot guard missing'; END IF;
END $check$;
ROLLBACK;
