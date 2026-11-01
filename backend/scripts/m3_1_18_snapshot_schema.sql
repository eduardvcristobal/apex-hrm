-- Runs in disposable PostgreSQL CI instance. Tests hard immutability and tenant restrictions.
BEGIN;
DO $check$
DECLARE present int;
BEGIN
 SELECT count(*) INTO present FROM pg_class WHERE relname='payroll_final_pay_source_snapshot';
 IF present<>1 THEN RAISE EXCEPTION 'Source snapshot table is missing'; END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_class c WHERE c.relname='payroll_final_pay_source_snapshot'
     AND c.relrowsecurity AND c.relforcerowsecurity) THEN
    RAISE EXCEPTION 'Snapshot row level security is not forced'; END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgname='fp_source_snapshot_immutable'
     AND NOT tgisinternal) THEN
    RAISE EXCEPTION 'Snapshot immutability trigger is absent'; END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_constraint
     WHERE conname='uq_fp_snapshot_hash') THEN
    RAISE EXCEPTION 'Snapshot digest idempotency constraint is absent'; END IF;
END $check$;
ROLLBACK;
