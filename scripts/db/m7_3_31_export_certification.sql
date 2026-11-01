\set ON_ERROR_STOP on
-- Test only on disposable PostgreSQL, never print protected CSV.
SELECT 1 / CASE WHEN count(*) = 1 THEN 1 ELSE 0 END AS encrypted_export_certified
FROM bank_file
WHERE id='00000000-0000-4000-8000-0000000000e7'
  AND content_legacy IS NULL AND purged_at IS NULL
  AND content_ciphertext LIKE ('e1:' || :'key_id' || ':%')
  AND content_ciphertext NOT LIKE '%000123456789%'
  AND length(content_sha256)=64;

SELECT 1 / CASE WHEN count(*) = 0 THEN 1 ELSE 0 END AS no_legacy_plaintext
FROM bank_file WHERE content_legacy IS NOT NULL
   OR (purged_at IS NULL AND content_ciphertext IS NULL);

DO $$
DECLARE blocked boolean := false;
BEGIN
 BEGIN
   INSERT INTO bank_file(tenant_id,run_id,bank_template_id,file_name,content_legacy)
   VALUES ('00000000-0000-4000-8000-0000000000e1',
           '00000000-0000-4000-8000-0000000000e5',
           '00000000-0000-4000-8000-0000000000e6',
           'attempted-plaintext.csv','FORBIDDEN');
 EXCEPTION WHEN raise_exception THEN
   blocked := true;
 END;
 IF NOT blocked THEN RAISE EXCEPTION 'V60 allowed plaintext bank export insertion'; END IF;
END $$;
