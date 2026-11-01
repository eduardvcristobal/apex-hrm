\set ON_ERROR_STOP on
-- M7.5: verify all integrated M5/M6 tenant-protected tables, after Flyway V58.
-- Catalog-only and read-only: safe for disposable CI; not proof of production DB role setup.
DO $$
DECLARE relation text; rls_enabled boolean; rls_forced boolean; matches int;
BEGIN
  IF (SELECT max(version::integer) FROM flyway_schema_history
      WHERE success AND version ~ '^[0-9]+$') <> 58 THEN
    RAISE EXCEPTION 'Expected integrated main at Flyway V58';
  END IF;
  IF EXISTS(SELECT 1 FROM flyway_schema_history WHERE NOT success) THEN
    RAISE EXCEPTION 'Unsuccessful Flyway migration';
  END IF;
  FOREACH relation IN ARRAY ARRAY[
    'performance_cycle','performance_goal','performance_review',
    'performance_development_plan','performance_employee_identity',
    'performance_goal_progress','performance_plan_milestone',
    'performance_milestone_evidence','ld_course','ld_enrollment','ld_skill',
    'ld_employee_skill','ld_certification','ld_employee_account'
  ] LOOP
    SELECT c.relrowsecurity,c.relforcerowsecurity INTO rls_enabled,rls_forced
      FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
     WHERE n.nspname='public' AND c.relname=relation
       AND c.relkind='r';
    IF NOT FOUND OR NOT COALESCE(rls_enabled,false) OR NOT COALESCE(rls_forced,false) THEN
      RAISE EXCEPTION 'RLS/FORCE RLS missing on %',relation;
    END IF;
    SELECT count(*) INTO matches FROM pg_policies
     WHERE schemaname='public' AND tablename=relation
       AND qual LIKE '%app_tenant_id()%'
       AND with_check LIKE '%app_tenant_id()%';
    IF matches < 1 THEN
      RAISE EXCEPTION 'Tenant-matched USING + WITH CHECK RLS policy missing on %',relation;
    END IF;
  END LOOP;
  RAISE NOTICE 'M7.5: verified RLS + WITH CHECK on all 14 integrated M5/M6 tenant tables';
END $$;
