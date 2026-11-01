\set ON_ERROR_STOP on
-- Only run on disposable CI databases; no employee data is printed.
DO $$
DECLARE latest integer; n integer;
BEGIN
  SELECT max(version::integer) INTO latest FROM flyway_schema_history
   WHERE success AND version ~ '^[0-9]+$';
  IF latest <> 59 THEN RAISE EXCEPTION 'Expected Flyway V59, found %',latest; END IF;
  SELECT count(*) INTO n FROM information_schema.columns WHERE table_schema='public'
      AND table_name='employee_bank_account' AND column_name='account_no';
  IF n <> 0 THEN RAISE EXCEPTION 'Old account_no column still present'; END IF;
  SELECT count(*) INTO n FROM information_schema.columns WHERE table_schema='public'
      AND table_name='employee_bank_account' AND column_name='account_no_ciphertext'
      AND data_type='text';
  IF n <> 1 THEN RAISE EXCEPTION 'Missing ciphertext field'; END IF;
  SELECT count(*) INTO n FROM pg_trigger
   WHERE tgrelid='employee_bank_account'::regclass AND tgname='employee_bank_cipher_only_insert'
     AND NOT tgisinternal;
  IF n <> 1 THEN RAISE EXCEPTION 'Missing plaintext write guard'; END IF;
  SELECT count(*) INTO n FROM employee_bank_account
   WHERE account_no_legacy IS NOT NULL OR account_no_ciphertext IS NULL;
  IF n <> 0 THEN RAISE EXCEPTION 'Legacy plaintext/missing ciphertext remains: %',n; END IF;
END $$;

-- Prove new legacy writes are blocked before any tenant/employee foreign keys are evaluated.
DO $$
DECLARE rejected boolean := false;
BEGIN
  BEGIN
    INSERT INTO employee_bank_account(tenant_id, employee_id, bank_code, account_no_legacy)
      VALUES ('00000000-0000-4000-8000-000000000001',
              '00000000-0000-4000-8000-000000000002','TEST','NEVER_PERSIST');
  EXCEPTION WHEN raise_exception THEN
    rejected := true;
  END;
  IF NOT rejected THEN RAISE EXCEPTION 'Plaintext insert unexpectedly succeeded'; END IF;
END $$$;