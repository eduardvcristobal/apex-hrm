\set ON_ERROR_STOP on
-- expected_key_id supplied by CI, e.g. k1 or k2; do not return ciphertext.
SELECT 1 / CASE WHEN COUNT(*) = 1 THEN 1 ELSE 0 END AS certified
  FROM employee_bank_account
 WHERE id='00000000-0000-4000-8000-0000000000d4'
   AND account_no_legacy IS NULL
   AND account_no_ciphertext LIKE ('b1:' || :'expected_key_id' || ':%')
   AND account_no_ciphertext NOT LIKE '%000123456789%';
