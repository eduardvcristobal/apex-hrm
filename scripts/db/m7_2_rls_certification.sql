\set ON_ERROR_STOP on
-- M7.2 destructive-safety design: everything, including temporary role and fixture rows,
-- is rolled back. Run ONLY against an isolated CI/disposable PostgreSQL database.
BEGIN;

DO $$
DECLARE latest integer; bad_count integer; migrated_count integer;
BEGIN
  SELECT max(version::int), count(*) INTO latest, migrated_count
  FROM flyway_schema_history WHERE success AND version ~ '^[0-9]+$';
  IF latest <> 58 THEN
    RAISE EXCEPTION 'Expected main Flyway version V58, got V%', latest;
  END IF;
  IF EXISTS (SELECT 1 FROM flyway_schema_history WHERE NOT success) THEN
    RAISE EXCEPTION 'Flyway schema history contains unsuccessful migration';
  END IF;
  SELECT count(*) INTO bad_count FROM (
    SELECT version FROM flyway_schema_history WHERE type='SQL' AND version IS NOT NULL
    GROUP BY version HAVING count(*) > 1
  ) x;
  IF bad_count > 0 THEN RAISE EXCEPTION 'Repeated SQL migration version in Flyway history'; END IF;
  RAISE NOTICE 'Flyway history verified, applied versioned SQL entries %', migrated_count;
END $$;

DO $$
DECLARE table_name text; matches integer; enabled boolean; forced boolean;
BEGIN
  FOREACH table_name IN ARRAY ARRAY[
    'performance_cycle','performance_goal','performance_review',
    'performance_development_plan','ld_course','ld_enrollment',
    'ld_skill','ld_employee_skill','ld_certification','ld_employee_account'
  ] LOOP
    SELECT c.relrowsecurity, c.relforcerowsecurity INTO enabled, forced
    FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE n.nspname='public' AND c.relname=table_name AND c.relkind IN ('r','p');
    IF NOT FOUND OR NOT COALESCE(enabled,false) OR NOT COALESCE(forced,false) THEN
      RAISE EXCEPTION 'RLS or FORCE RLS missing for %',table_name;
    END IF;
    SELECT count(*) INTO matches
    FROM pg_policies WHERE schemaname='public' AND tablename=table_name
      AND qual ILIKE '%app_tenant_id()%'
      AND with_check ILIKE '%app_tenant_id()%';
    IF matches = 0 THEN RAISE EXCEPTION 'Tenant-scoped USING/WITH CHECK policy missing for %',table_name; END IF;
  END LOOP;
  RAISE NOTICE 'RLS policy catalog verified for 10 performance and learning tables';
END $$;

-- Known synthetic fixture tenants. Inserted only inside this rollback-only transaction.
INSERT INTO tenant(id,code,name,slug,display_name)
VALUES
 ('00000000-0000-4000-8000-0000000000a1','m72-a','M72 isolated A','m72-a','M72 isolated A'),
 ('00000000-0000-4000-8000-0000000000b2','m72-b','M72 isolated B','m72-b','M72 isolated B');
INSERT INTO ld_skill(id,tenant_id,name,category)
VALUES
 ('00000000-0000-4000-8000-0000000000c1','00000000-0000-4000-8000-0000000000a1','M72 unique A','CI'),
 ('00000000-0000-4000-8000-0000000000c2','00000000-0000-4000-8000-0000000000b2','M72 unique B','CI');

CREATE ROLE m72_restricted_probe NOLOGIN NOBYPASSRLS;
GRANT USAGE ON SCHEMA public TO m72_restricted_probe;
GRANT SELECT,INSERT,UPDATE,DELETE ON ld_skill TO m72_restricted_probe;
GRANT EXECUTE ON FUNCTION app_tenant_id() TO m72_restricted_probe;
SET LOCAL ROLE m72_restricted_probe;
SET LOCAL app.tenant_id = '00000000-0000-4000-8000-0000000000a1';

DO $$
DECLARE seen integer;
BEGIN
 SELECT count(*) INTO seen FROM ld_skill WHERE name LIKE 'M72 unique %';
 IF seen<>1 THEN RAISE EXCEPTION 'Cross-tenant SELECT leaked records: %', seen; END IF;
 IF EXISTS(SELECT 1 FROM ld_skill WHERE name='M72 unique B') THEN
   RAISE EXCEPTION 'Tenant A can read tenant B';
 END IF;
END $$;

-- Cross-tenant INSERT must fail because WITH CHECK is active.
DO $$
BEGIN
  BEGIN
    INSERT INTO ld_skill(tenant_id,name) VALUES
      ('00000000-0000-4000-8000-0000000000b2','M72 forbidden cross-tenant');
    RAISE EXCEPTION 'RLS permitted cross-tenant INSERT';
  EXCEPTION WHEN insufficient_privilege OR check_violation THEN NULL;
  END;
END $$;

DO $$
BEGIN
  UPDATE ld_skill SET name='M72 cross-tenant mutated' WHERE name='M72 unique B';
  IF FOUND THEN RAISE EXCEPTION 'RLS allowed updating tenant B from tenant A'; END IF;
  DELETE FROM ld_skill WHERE name='M72 unique B';
  IF FOUND THEN RAISE EXCEPTION 'RLS allowed deleting tenant B from tenant A'; END IF;
END $$;

SET LOCAL app.tenant_id = '';
DO $$
BEGIN
  IF EXISTS(SELECT 1 FROM ld_skill WHERE name LIKE 'M72 unique %') THEN
     RAISE EXCEPTION 'Missing tenant context returned records';
  END IF;
END $$;

SET LOCAL app.tenant_id = '00000000-0000-4000-8000-0000000000b2';
DO $$
DECLARE seen integer;
BEGIN
 SELECT count(*) INTO seen FROM ld_skill WHERE name LIKE 'M72 unique %';
 IF seen<>1 THEN RAISE EXCEPTION 'Switch to B not properly isolated: %',seen; END IF;
 IF EXISTS(SELECT 1 FROM ld_skill WHERE name='M72 unique A') THEN
    RAISE EXCEPTION 'Tenant B can read tenant A';
 END IF;
END $$;

RESET ROLE;
ROLLBACK;
-- Require this script to exit successfully; no fixture data or role remains.
