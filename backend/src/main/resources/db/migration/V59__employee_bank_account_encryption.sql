-- M7.3: do not expose legacy plaintext through ORM. Startup migrator encrypts it
-- before API readiness and scrubs the legacy column. Do not drop the legacy
-- column until migration coverage and sanitized restore rehearsals are verified.
ALTER TABLE employee_bank_account RENAME COLUMN account_no TO account_no_legacy;
ALTER TABLE employee_bank_account ALTER COLUMN account_no_legacy DROP NOT NULL;
ALTER TABLE employee_bank_account ADD COLUMN account_no_ciphertext text;
ALTER TABLE employee_bank_account ADD CONSTRAINT ck_employee_bank_account_value
    CHECK (account_no_ciphertext IS NOT NULL OR account_no_legacy IS NOT NULL);

-- Old clients must fail closed: no new plaintext may be inserted.
CREATE OR REPLACE FUNCTION reject_employee_bank_plaintext_insert()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.account_no_legacy IS NOT NULL THEN
    RAISE EXCEPTION 'Plaintext bank account writes are forbidden';
  END IF;
  IF NEW.account_no_ciphertext IS NULL THEN
    RAISE EXCEPTION 'Encrypted bank account required';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER employee_bank_cipher_only_insert
BEFORE INSERT ON employee_bank_account
FOR EACH ROW EXECUTE FUNCTION reject_employee_bank_plaintext_insert();

COMMENT ON COLUMN employee_bank_account.account_no_legacy IS
 'TRANSITION ONLY: plaintext from pre-V59 rows, scrubbed by application startup migration. Never insert new values.';
