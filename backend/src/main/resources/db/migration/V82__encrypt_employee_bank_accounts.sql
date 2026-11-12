ALTER TABLE employee_bank_account
    ADD COLUMN account_no_ciphertext TEXT,
    ADD COLUMN account_name_ciphertext TEXT;

ALTER TABLE employee_bank_account
    ALTER COLUMN account_no DROP NOT NULL;

ALTER TABLE employee_bank_account
    ALTER COLUMN account_no TYPE TEXT,
    ALTER COLUMN account_name TYPE TEXT;

COMMENT ON COLUMN employee_bank_account.account_no IS
    'Legacy plaintext value; clear after application-level encryption migration.';
COMMENT ON COLUMN employee_bank_account.account_name IS
    'Legacy plaintext value; clear after application-level encryption migration.';
