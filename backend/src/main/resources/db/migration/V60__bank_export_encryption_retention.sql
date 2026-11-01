-- M7.3.31: preserve old content ONLY until the mandatory transactional startup backfill.
-- Forward-only migration; never edit historical V1-V59 files.
ALTER TABLE bank_file RENAME COLUMN content TO content_legacy;
ALTER TABLE bank_file ALTER COLUMN content_legacy DROP NOT NULL;
ALTER TABLE bank_file ADD COLUMN content_ciphertext text;
ALTER TABLE bank_file ADD COLUMN content_sha256 char(64);
ALTER TABLE bank_file ADD COLUMN purged_at timestamptz;
ALTER TABLE bank_file ADD COLUMN legal_hold boolean NOT NULL DEFAULT false;

ALTER TABLE bank_file ADD CONSTRAINT ck_bank_file_encrypted_or_purged CHECK (
 (purged_at IS NULL AND (content_legacy IS NOT NULL OR content_ciphertext IS NOT NULL))
 OR (purged_at IS NOT NULL AND content_legacy IS NULL AND content_ciphertext IS NULL)
);
CREATE INDEX ix_bank_file_retention ON bank_file (tenant_id, created_at)
 WHERE purged_at IS NULL AND legal_hold = false;

CREATE OR REPLACE FUNCTION bank_file_plaintext_write_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP = 'INSERT' AND NEW.content_legacy IS NOT NULL THEN
  RAISE EXCEPTION 'Bank export plaintext inserts are prohibited';
 END IF;
 IF TG_OP = 'UPDATE' AND NEW.content_legacy IS NOT NULL
    AND NEW.content_legacy IS DISTINCT FROM OLD.content_legacy THEN
  RAISE EXCEPTION 'Bank export plaintext updates are prohibited';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER bank_file_cipher_only_writes
BEFORE INSERT OR UPDATE ON bank_file
FOR EACH ROW EXECUTE FUNCTION bank_file_plaintext_write_guard();

COMMENT ON COLUMN bank_file.content_legacy IS
 'Transitional V60 plaintext; must be NULL after startup encrypted backfill. Never insert new plaintext.';
COMMENT ON COLUMN bank_file.content_sha256 IS
 'SHA-256 digest of original plaintext bank CSV retained for post-purge audit; not an account-number hash.';
