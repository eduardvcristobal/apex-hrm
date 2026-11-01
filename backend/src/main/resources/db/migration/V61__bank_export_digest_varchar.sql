-- Forward-only correction for Hibernate string type validation; leave V60 checksum intact.
ALTER TABLE bank_file ALTER COLUMN content_sha256 TYPE varchar(64)
  USING trim(trailing FROM content_sha256)::varchar(64);
